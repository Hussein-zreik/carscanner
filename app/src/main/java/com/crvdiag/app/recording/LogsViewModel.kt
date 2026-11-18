package com.crvdiag.app.recording

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.data.CrvDatabase
import com.crvdiag.data.DriveLogEntity
import com.crvdiag.data.ScanReportEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    val dir = File(application.filesDir, "logs")
    val reportsDir = File(application.filesDir, "reports")
    val capturesDir = File(application.filesDir, "captures")

    private val _captures = MutableStateFlow<List<File>>(emptyList())
    /** Phase 2 capture files, newest first. */
    val captures: StateFlow<List<File>> = _captures.asStateFlow()

    fun refreshCaptures() = viewModelScope.launch {
        _captures.value = withContext(Dispatchers.IO) {
            capturesDir.listFiles { f -> f.name.endsWith(".log") }.orEmpty().sortedByDescending { it.name }
        }
    }

    fun deleteCapture(f: File) = viewModelScope.launch {
        withContext(Dispatchers.IO) { f.delete() }
        refreshCaptures()
    }
    private val dao by lazy { CrvDatabase.get(application).driveLogDao() }
    private val reportDao by lazy { CrvDatabase.get(application).scanReportDao() }

    private val _reports = MutableStateFlow<List<ScanReportEntity>>(emptyList())
    val reports: StateFlow<List<ScanReportEntity>> = _reports.asStateFlow()

    private val _logs = MutableStateFlow<List<DriveLogEntity>>(emptyList())
    val logs: StateFlow<List<DriveLogEntity>> = _logs.asStateFlow()

    private var observing = false

    fun observe() {
        if (observing) return
        observing = true
        refreshCaptures()
        viewModelScope.launch { dao.observeAll().collect { _logs.value = it } }
        viewModelScope.launch { reportDao.observeAll().collect { _reports.value = it } }
    }

    fun delete(log: DriveLogEntity) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            File(dir, log.csvFile).delete()
            File(dir, log.rawFile).delete()
            dao.delete(log.id)
        }
    }

    fun deleteReport(r: ScanReportEntity) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            File(reportsDir, r.fileName).delete()
            reportDao.delete(r.id)
        }
    }

    /** Screenshot tests only. */
    internal fun showPreview(list: List<DriveLogEntity>) {
        observing = true
        _logs.value = list
    }
}
