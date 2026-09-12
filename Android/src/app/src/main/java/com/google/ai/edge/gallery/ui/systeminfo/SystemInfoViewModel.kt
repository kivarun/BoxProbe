package com.google.ai.edge.gallery.ui.systeminfo

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.systeminfo.SystemInfoCollector
import com.google.ai.edge.gallery.systeminfo.SystemInfoSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SystemInfoViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
) : ViewModel() {

  private val _snapshot = MutableStateFlow<SystemInfoSnapshot?>(null)
  val snapshot = _snapshot.asStateFlow()

  private val _collecting = MutableStateFlow(false)
  val collecting = _collecting.asStateFlow()

  init {
    collect()
  }

  fun collect() {
    if (_collecting.value) return
    viewModelScope.launch {
      _collecting.value = true
      val result =
        withContext(Dispatchers.Default) { SystemInfoCollector.collect(appContext) }
      _snapshot.value = result
      _collecting.value = false
    }
  }
}
