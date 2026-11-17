package com.crvdiag.app.recording

import com.crvdiag.obd.LiveSample
import com.crvdiag.obd.Pids
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneOffset

class DriveRecorderTest {
    @TempDir lateinit var dir: File

    @Test
    fun `writes CSV rows and raw log lines`() {
        val start = 1_000L
        val rec = DriveRecorder(dir, start, listOf(0x0C, 0x05, 0x03), ZoneOffset.UTC)
        rec.rawLine("00:00:01.000 TX 010C0503\\r")
        rec.append(LiveSample(mapOf(
            0x0C to Pids.decode(0x0C, listOf(0x0D, 0x48))!!,
            0x05 to Pids.decode(0x05, listOf(0x7D))!!,
            0x03 to Pids.decode(0x03, listOf(0x02, 0x00))!!,
        ), emptySet(), 1_250L))
        rec.append(LiveSample(mapOf(0x0C to Pids.decode(0x0C, listOf(0x0D, 0x50))!!), setOf(0x05, 0x03), 1_500L))
        rec.close()

        assertEquals("drive_1970-01-01_00-00-01.csv", rec.csvFile.name)
        val lines = rec.csvFile.readText(Charsets.UTF_8).removePrefix("\uFEFF").split("\r\n")
        assertEquals("time,elapsed_s,Engine RPM (rpm),Coolant temperature (°C),Fuel system status", lines[0])
        assertEquals("00:00:01.250,0.250,850,85,Closed loop (normal)", lines[1])
        assertEquals("00:00:01.500,0.500,852,,", lines[2])
        assertEquals(2, rec.rows)
        assertTrue(rec.rawFile.readText().contains("TX 010C0503\\r"))
    }
}
