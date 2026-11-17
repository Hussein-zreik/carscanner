package com.crvdiag.app.recording

import com.crvdiag.data.CsvWriter
import com.crvdiag.obd.LiveSample
import com.crvdiag.obd.Pids
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes one drive: a CSV of decoded values (one row per polling cycle, base units in the
 * header) and the raw adapter log in the testdata/ format. Thread-safe; Android-free.
 */
class DriveRecorder(
    dir: File,
    val startedAtMs: Long,
    private val pids: List<Int>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AutoCloseable {
    private val stamp = FILE_STAMP.format(Instant.ofEpochMilli(startedAtMs).atZone(zone))
    val csvFile = File(dir.apply { mkdirs() }, "drive_$stamp.csv")
    val rawFile = File(dir, "drive_$stamp.log")

    private val csv = CsvWriter(csvFile.bufferedWriter(Charsets.UTF_8), header())
    private val raw: BufferedWriter = rawFile.bufferedWriter(Charsets.UTF_8).apply {
        write("# CR-V Diag drive recording · started ${Instant.ofEpochMilli(startedAtMs).atZone(zone)}\n")
        write("# Format: HH:mm:ss.SSS TX|RX|INFO <text>, control characters escaped.\n")
    }

    var rows = 0
        private set
    private var closed = false

    private fun header(): List<String> = listOf("time", "elapsed_s") + pids.map { pid ->
        val info = Pids.LENGTH[pid]?.let { Pids.decode(pid, List(it) { 0 }) }
        val name = info?.name ?: "PID %02X".format(pid)
        val unit = info?.unit
        if (unit.isNullOrEmpty()) name else "$name ($unit)"
    }

    @Synchronized
    fun append(sample: LiveSample) {
        if (closed) return
        val time = TIME.format(Instant.ofEpochMilli(sample.timeMs).atZone(zone))
        val elapsed = "%.3f".format(Locale.US, (sample.timeMs - startedAtMs) / 1000.0)
        csv.row(listOf(time, elapsed) + pids.map { pid ->
            sample.values[pid]?.let { v -> v.text ?: v.value?.let { "%.3f".format(Locale.US, it).trimEnd('0').trimEnd('.') } }
        })
        if (++rows % 20 == 0) csv.flush()
    }

    @Synchronized
    fun rawLine(line: String) {
        if (closed) return
        raw.write(line)
        raw.write("\n")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        csv.close()
        raw.close()
    }

    companion object {
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
