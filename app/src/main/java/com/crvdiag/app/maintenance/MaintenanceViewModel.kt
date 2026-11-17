package com.crvdiag.app.maintenance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.data.CrvDatabase
import com.crvdiag.data.DueStatus
import com.crvdiag.data.MaintenanceDefaults
import com.crvdiag.data.MaintenanceItemEntity
import com.crvdiag.data.MaintenanceLogic
import com.crvdiag.data.OdometerEstimate
import com.crvdiag.data.OdometerReadingEntity
import com.crvdiag.data.ServiceRecordEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ItemStatus(val item: MaintenanceItemEntity, val last: ServiceRecordEntity?, val status: DueStatus)

data class MaintenanceState(
    val odometer: OdometerEstimate? = null,
    val items: List<ItemStatus> = emptyList(),
    val records: List<ServiceRecordEntity> = emptyList(),
    val readings: List<OdometerReadingEntity> = emptyList(),
) {
    /** Most urgent first: overdue, due soon, unknown, OK (then by remaining distance/time). */
    val byUrgency: List<ItemStatus>
        get() = items.sortedWith(compareBy({ rank(it.status) }, { it.item.sortOrder }))

    val overdueCount get() = items.count { it.status is DueStatus.Overdue }
    val soonCount get() = items.count { it.status is DueStatus.Soon }
    val unknownCount get() = items.count { it.status is DueStatus.Unknown }

    private fun rank(s: DueStatus) = when (s) {
        is DueStatus.Overdue -> 0
        is DueStatus.Soon -> 1
        DueStatus.Unknown -> 2
        is DueStatus.Ok -> 3
    }
}

class MaintenanceViewModel(application: Application) : AndroidViewModel(application) {
    private val dao by lazy { CrvDatabase.get(application).maintenanceDao() }
    private val _state = MutableStateFlow(MaintenanceState())
    val state: StateFlow<MaintenanceState> = _state.asStateFlow()
    private var observing = false

    fun observe() {
        if (observing) return
        observing = true
        viewModelScope.launch {
            dao.insertDefaults(MaintenanceDefaults.ITEMS)
            combine(dao.observeItems(), dao.observeRecords(), dao.observeReadings()) { items, records, readings ->
                build(items, records, readings, LocalDate.now())
            }.collect { _state.value = it }
        }
    }

    fun addReading(miles: Int, day: LocalDate) = viewModelScope.launch {
        dao.insertReading(OdometerReadingEntity(epochDay = day.toEpochDay(), miles = miles))
    }

    fun deleteReading(id: Long) = viewModelScope.launch { dao.deleteReading(id) }

    /** A service with a known mileage is also an odometer reading. */
    fun logService(itemKey: String, day: LocalDate, miles: Int?, notes: String?) = viewModelScope.launch {
        dao.insertRecord(ServiceRecordEntity(itemKey = itemKey, epochDay = day.toEpochDay(), odometerMiles = miles, notes = notes?.ifBlank { null }))
        if (miles != null && MaintenanceLogic.checkReading(miles, day, _state.value.readings) == null) {
            dao.insertReading(OdometerReadingEntity(epochDay = day.toEpochDay(), miles = miles))
        }
    }

    fun deleteRecord(id: Long) = viewModelScope.launch { dao.deleteRecord(id) }

    fun updateInterval(key: String, miles: Int?, months: Int?) = viewModelScope.launch { dao.updateInterval(key, miles, months) }

    /** Screenshot tests only. */
    internal fun showPreview(
        items: List<MaintenanceItemEntity>,
        records: List<ServiceRecordEntity>,
        readings: List<OdometerReadingEntity>,
        today: LocalDate,
    ) {
        observing = true
        _state.value = build(items, records, readings, today)
    }

    companion object {
        fun build(
            items: List<MaintenanceItemEntity>,
            records: List<ServiceRecordEntity>,
            readings: List<OdometerReadingEntity>,
            today: LocalDate,
        ): MaintenanceState {
            val odometer = MaintenanceLogic.estimateMiles(readings, today)
            val latestByItem = records.groupBy { it.itemKey }.mapValues { (_, list) -> list.maxByOrNull { it.epochDay } }
            val statuses = items.map { item ->
                val last = latestByItem[item.key]
                ItemStatus(item, last, MaintenanceLogic.status(item, last, odometer?.miles, today))
            }
            return MaintenanceState(odometer, statuses, records, readings)
        }
    }
}
