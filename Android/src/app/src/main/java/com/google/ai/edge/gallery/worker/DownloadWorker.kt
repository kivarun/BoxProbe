/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.data.KEY_MODEL_COMMIT_HASH
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_URLS
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_ZIP
import com.google.ai.edge.gallery.data.KEY_MODEL_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_START_UNZIPPING
import com.google.ai.edge.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_UNZIPPED_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_URL
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadWorker"

data class UrlAndFileName(val url: String, val fileName: String)

/**
 * Decides how a download attempt must treat an existing partial (tmp) file.
 *
 * @param responseCode HTTP response code for this attempt.
 * @param rangeRequested true when a `Range: bytes=<tmp>-` header was sent.
 * @param contentRange raw `Content-Range` header value, e.g. `bytes 100-999/1000` (may be null).
 * @param tmpFileSize size of the partial tmp file before this attempt started appending.
 * @param contentLength raw `Content-Length` header value for non-partial responses (may be null).
 */
enum class ResumeDecision { RESUME, RESTART_FROM_ZERO, FINALIZE }

fun decideResume(
  responseCode: Int,
  rangeRequested: Boolean,
  contentRange: String?,
  tmpFileSize: Long,
  contentLength: Long,
): ResumeDecision {
  when (responseCode) {
    HttpURLConnection.HTTP_PARTIAL -> {
      val parsed = parseContentRange(contentRange) ?: return ResumeDecision.RESTART_FROM_ZERO
      val (startByte, endByte) = parsed
      // The appended part must continue exactly at the end of the existing tmp file.
      if (tmpFileSize != startByte) {
        return ResumeDecision.RESTART_FROM_ZERO
      }
      // Range fully satisfied already: nothing to write.
      if (startByte > endByte) {
        return ResumeDecision.FINALIZE
      }
      return ResumeDecision.RESUME
    }
    HttpURLConnection.HTTP_OK -> {
      // Server ignored our Range request and restarts from the beginning: appending
      // would duplicate content onto the partial file.
      if (rangeRequested && tmpFileSize > 0) {
        return ResumeDecision.RESTART_FROM_ZERO
      }
      return ResumeDecision.RESUME
    }
    // Range Not Satisfiable: the tmp file may already contain the complete file.
    416 -> {
      val total = parseContentRange(contentRange)?.second?.plus(1) ?: contentLength
      if (total > 0 && tmpFileSize == total) {
        return ResumeDecision.FINALIZE
      }
      return ResumeDecision.RESTART_FROM_ZERO
    }
    else -> return ResumeDecision.RESTART_FROM_ZERO
  }
}

/** Parses `bytes S-E/T` into (S, E); returns null for malformed values. */
fun parseContentRange(contentRange: String?): Pair<Long, Long>? {
  if (contentRange == null) {
    return null
  }
  return try {
    val rangeParts = contentRange.substringAfter("bytes ").split("/")
    val byteRange = rangeParts[0].split("-")
    val startByte = byteRange[0].toLong()
    val endByte = byteRange[1].toLong()
    Pair(startByte, endByte)
  } catch (_: Exception) {
    null
  }
}

class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private val externalFilesDir = context.getExternalFilesDir(null)

  override suspend fun doWork(): Result {
    // Box: Block downloads when offline mode is enabled
    try {
      com.google.ai.edge.gallery.security.OfflineMode.assertOnlineOrThrow()
    } catch (e: com.google.ai.edge.gallery.security.OfflineMode.OfflineModeException) {
      return Result.failure(
        Data.Builder()
          .putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message)
          .build()
      )
    }

    val fileUrl = inputData.getString(KEY_MODEL_URL)
    val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
    val version = inputData.getString(KEY_MODEL_COMMIT_HASH)!!
    val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
    val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)!!
    val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
    val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
    val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
    val extraDataFileNames =
      inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
    val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

    return withContext(Dispatchers.IO) {
      if (fileUrl == null || fileName == null) {
        Result.failure()
      } else {
        return@withContext try {
          // Collect data for all files.
          val allFiles: MutableList<UrlAndFileName> = mutableListOf()
          allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
          for (index in extraDataFileUrls.indices) {
            allFiles.add(
              UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
            )
          }
          Log.d(TAG, "About to download: $allFiles")

          // Download them in sequence.
          // TODO: maybe consider downloading them in parallel.
          var downloadedBytes = 0L
          val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
          val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()
          for (file in allFiles) {
            val url = URL(file.url)

            // Prepare output file's dir.
            val outputDir =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version).joinToString(separator = File.separator),
              )
            if (!outputDir.exists()) {
              outputDir.mkdirs()
            }

            // Read the tmp file and see if it is partially downloaded.
            val outputTmpFile =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT")
                  .joinToString(separator = File.separator),
              )

            // Claim exclusive write access to the tmp file. WorkManager retries and
            // REPLACE-policy re-enqueues can start a new worker while a previous
            // (canceled-but-draining) instance still writes to the same tmp file;
            // two concurrent append streams produce a corrupted file.
            val lockChannel =
              java.io.RandomAccessFile(outputTmpFile, "rw").channel
            val fileLock = lockChannel.tryLock()
            if (fileLock == null) {
              Log.w(TAG, "Tmp file '${outputTmpFile.name}' is locked by another download writer.")
              lockChannel.close()
              return@withContext Result.retry()
            }

            try {
            val resumeAttempt = outputTmpFile.length() > 0
            if (resumeAttempt) {
              Log.d(
                TAG,
                "File '${outputTmpFile.name}' partial size: ${outputTmpFile.length()}. Trying to resume download",
              )
            }
            val connection =
              openDownloadConnection(
                url = url,
                accessToken = accessToken,
                resumeFrom = if (resumeAttempt) outputTmpFile.length() else 0,
              )
            connection.connect()
            val responseCode = connection.responseCode
            Log.d(TAG, "response code: $responseCode")

            val decision =
              decideResume(
                responseCode = responseCode,
                rangeRequested = resumeAttempt,
                contentRange = connection.getHeaderField("Content-Range"),
                tmpFileSize = if (resumeAttempt) outputTmpFile.length() else 0,
                contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: 0,
              )
            Log.d(TAG, "Resume decision: $decision")

            val append =
              when (decision) {
                ResumeDecision.RESUME -> true
                ResumeDecision.FINALIZE -> false
                ResumeDecision.RESTART_FROM_ZERO -> {
                  // Server ignored the Range request or the range start does not match
                  // the tmp file: appending would produce a corrupted file.
                  Log.d(TAG, "Restarting download from beginning: deleting tmp file.")
                  outputTmpFile.delete()
                  false
                }
              }

            // Expected size for the final integrity check.
            val expectedTotalBytes =
              when (responseCode) {
                HttpURLConnection.HTTP_PARTIAL ->
                  parseContentRange(connection.getHeaderField("Content-Range"))?.second?.plus(1) ?: 0
                HttpURLConnection.HTTP_OK ->
                  connection.getHeaderField("Content-Length")?.toLongOrNull() ?: 0
                else -> 0
              }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputTmpFile, append)

            // Count the already-present partial bytes as progress for resumed downloads.
            if (append && resumeAttempt) {
              downloadedBytes += outputTmpFile.length()
            }

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int
            var lastSetProgressTs: Long = 0
            var deltaBytes = 0L
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
              outputStream.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead
              deltaBytes += bytesRead

              // Report progress every 200 ms.
              val curTs = System.currentTimeMillis()
              if (curTs - lastSetProgressTs > 200) {
                // Calculate download rate.
                var bytesPerMs = 0f
                if (lastSetProgressTs != 0L) {
                  if (bytesReadSizeBuffer.size == 5) {
                    bytesReadSizeBuffer.removeAt(0)
                  }
                  bytesReadSizeBuffer.add(deltaBytes)
                  if (bytesReadLatencyBuffer.size == 5) {
                    bytesReadLatencyBuffer.removeAt(0)
                  }
                  bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                  deltaBytes = 0L
                  bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                }

                // Calculate remaining seconds
                var remainingMs = 0f
                if (bytesPerMs > 0f && totalBytes > 0L) {
                  remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                }

                setProgress(
                  Data.Builder()
                    .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                    .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                    .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                    .build()
                )
                Log.d(TAG, "downloadedBytes: $downloadedBytes")
                lastSetProgressTs = curTs
              }
            }

            outputStream.close()
            inputStream.close()
            connection.disconnect()

            // Integrity check: the downloaded file must match the server-reported size.
            // A mismatch means the tmp file was corrupted (e.g. by a stale writer);
            // delete it so the retry starts from scratch.
            val finalSize = outputTmpFile.length()
            if (expectedTotalBytes > 0 && finalSize != expectedTotalBytes) {
              val msg =
                "Downloaded file size mismatch for '${outputTmpFile.name}': " +
                  "expected $expectedTotalBytes, got $finalSize. Deleting tmp file."
              Log.e(TAG, msg)
              outputTmpFile.delete()
              throw IOException(msg)
            }

            // Rename the tmp file to the original file name by removing the tmp file ext.
            val originalFilePath = outputTmpFile.absolutePath.replace(".$TMP_FILE_EXT", "")
            val originalFile = File(originalFilePath)
            if (originalFile.exists()) {
              originalFile.delete()
            }
            outputTmpFile.renameTo(originalFile)
            Log.d(TAG, "Download done")

            // Unzip if the downloaded file is a zip.
            if (isZip && unzippedDir != null) {
              setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

              // Prepare target dir.
              val destDir =
                File(
                  externalFilesDir,
                  listOf(modelDir, version, unzippedDir).joinToString(File.separator),
                )
              if (!destDir.exists()) {
                destDir.mkdirs()
              }

              // Unzip.
              val unzipBuffer = ByteArray(4096)
              val zipFilePath =
                "${externalFilesDir}${File.separator}$modelDir${File.separator}$version${File.separator}${fileName}"
              val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath)))
              var zipEntry: ZipEntry? = zipIn.nextEntry

              while (zipEntry != null) {
                val filePath = destDir.absolutePath + File.separator + zipEntry.name

                // Extract files.
                if (!zipEntry.isDirectory) {
                  // extract file
                  val bos = FileOutputStream(filePath)
                  bos.use { curBos ->
                    var len: Int
                    while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                      curBos.write(unzipBuffer, 0, len)
                    }
                  }
                }
                // Create dir.
                else {
                  val dir = File(filePath)
                  dir.mkdirs()
                }

                zipIn.closeEntry()
                zipEntry = zipIn.nextEntry
              }
              zipIn.close()

              // Delete the original file.
              val zipFile = File(zipFilePath)
              zipFile.delete()
            }
            } finally {
              fileLock.release()
              lockChannel.close()
            }
          }
          Result.success()
        } catch (e: IOException) {
          Log.e(TAG, e.message, e)
          Result.failure(
            Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message).build()
          )
        }
      }
    }
  }

  private fun openDownloadConnection(
    url: URL,
    accessToken: String?,
    resumeFrom: Long,
  ): HttpURLConnection {
    val connection = url.openConnection() as HttpURLConnection
    if (accessToken != null) {
      Log.d(TAG, "Using access token: ${accessToken.subSequence(0, 10)}...")
      connection.setRequestProperty("Authorization", "Bearer $accessToken")
    }
    if (resumeFrom > 0) {
      connection.setRequestProperty("Range", "bytes=$resumeFrom-")
      // Force the server to send non-compressed data to make download resuming work.
      connection.setRequestProperty("Accept-Encoding", "identity")
    }
    // Do not let a stalled server block a canceled worker forever: an interrupted
    // (canceled) worker keeps draining its stream and would otherwise hold the
    // tmp-file lock indefinitely.
    connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
    connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
    return connection
  }
}

private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
