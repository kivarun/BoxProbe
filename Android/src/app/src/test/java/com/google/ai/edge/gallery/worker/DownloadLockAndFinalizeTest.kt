package com.google.ai.edge.gallery.worker

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Locking design and finalize-size gate for the model downloader. */
@RunWith(JUnit4::class)
class DownloadLockAndFinalizeTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  // ---- lock path derivation ----

  @Test
  fun lockPath_differsFromTmpPath_andIsDeterministic() {
    val tmp = File(tempFolder.root, "model.litertlm.gallerytmp")
    val lock = downloadLockFileFor(tmp)

    assertNotEquals(tmp.absolutePath, lock.absolutePath)
    assertEquals("model.litertlm.gallerytmp.lock", lock.name)
    assertEquals(tmp.parentFile.absolutePath, lock.parentFile.absolutePath)
    // Same tmp -> same lock target.
    assertEquals(lock.absolutePath, downloadLockFileFor(File(tmp.absolutePath)).absolutePath)
  }

  @Test
  fun lockSurvivesTmpDeleteAndRecreate_secondWorkerBlocked() {
    val tmp = File(tempFolder.root, "model.litertlm.gallerytmp")
    val lockFile = downloadLockFileFor(tmp)
    tmp.writeText("partial content")

    val first = RandomAccessFile(lockFile, "rw")
    val firstLock = first.channel.tryLock()
    assertNotNull("initial acquire must succeed", firstLock)

    // Inside the attempt: tmp is deleted and recreated (restart-from-zero). The
    // lock file must keep its inode and keep blocking everyone else.
    tmp.delete()
    tmp.writeText("restarted from zero")

    val second = RandomAccessFile(lockFile, "rw")
    // Within one process a blocked tryLock either returns null or throws
    // OverlappingFileLockException — both mean "not acquirable".
    val secondAcquire =
      try {
        second.channel.tryLock()
      } catch (_: java.nio.channels.OverlappingFileLockException) {
        null
      }
    assertNull("second worker must not acquire the lock while the first one holds it", secondAcquire)

    firstLock.release()
    first.close()

    // After release the next worker acquires the SAME lock file.
    val secondLock =
      try {
        second.channel.tryLock()
      } catch (_: java.nio.channels.OverlappingFileLockException) {
        null
      }
    assertNotNull("lock must be acquirable after release", secondLock)
    secondLock?.release()
    second.close()
  }

  @Test
  fun lockFile_isNotTreatedAsModelArtifact() {
    // The sidecar lock file lives next to the model file; discovery must keep using
    // exact names and the .tmp suffix only.
    val name = "gemma3.litertlm"
    assertTrue(name.endsWith(".litertlm"))
    val lockName = name + DOWNLOAD_LOCK_FILE_SUFFIX
    assertFalse(lockName.endsWith(".litertlm"))
    assertFalse(lockName.endsWith(".gallerytmp"))
  }

  // ---- finalize size gate ----

  @Test
  fun finalize_acceptedForUnsatisfiedRangeMatchingTmp() {
    val parsed = parseContentRange("bytes */1000")
    assertTrue(finalizeSizeAccepted(parsed, tmpFileSize = 1000))
  }

  @Test
  fun finalize_rejectedOnTmpSizeMismatch() {
    val parsed = parseContentRange("bytes */1000")
    assertFalse(finalizeSizeAccepted(parsed, tmpFileSize = 500))
  }

  @Test
  fun finalize_rejectedForFullRangeShape() {
    val parsed = parseContentRange("bytes 1000-999/1000")
    assertFalse(finalizeSizeAccepted(parsed, tmpFileSize = 1000))
  }

  @Test
  fun finalize_rejectedWithoutParsedRange() {
    assertFalse(finalizeSizeAccepted(null, tmpFileSize = 1000))
    assertFalse(finalizeSizeAccepted(parseContentRange("bytes 500-999/*"), tmpFileSize = 999))
  }
}
