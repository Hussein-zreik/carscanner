package com.crvdiag.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One diagnostic session with the car (connect -> read -> disconnect). */
@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    /** ELM327 protocol number from ATDPN, e.g. "6" for ISO 15765-4 CAN 11-bit 500 kbps. */
    val protocol: String? = null,
    val note: String? = null,
)
