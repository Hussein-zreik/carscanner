package com.crvdiag.app.recording

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.data.CrvDatabase
import com.crvdiag.data.DriveLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    val dir = File(application.filesDir, "logs")
    private val dao by lazy { CrvDatabase.get(application).driveLogDao() }

    private val _logs = MutableStateFlow<List<DriveLogEntity>>(emptyList())
    val logs: StateFlow<List<DriveLogEntity>> = _logs.asStateFlow()

    private var observing = false

    fun observe() {
        if (observing) return
        observing = true
        viewModelScope.launch { dao.observeAll().collect { _logs.value = it } }
    }

    fun delete(log: DriveLogEntity) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            File(dir, log.csvFile).delete()
            File(dir, log.rawFile).delete()
            dao.delete(log.id)
        }
    }

    /** Screenshot tests only. */
    internal fun showPreview(list: List<DriveLogEntity>) {
        observing = true
        _logs.value = list
    }
}
