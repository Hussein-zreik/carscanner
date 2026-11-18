package com.crvdiag.app.report

import android.content.Context
import com.crvdiag.data.CrvDatabase
import com.crvdiag.data.ScanReportEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter

/** Builds, writes and registers a scan report PDF (used by Codes and the Car test). */
object ReportSaver {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    suspend fun save(context: Context, input: ReportInput): Pair<List<Block>, File> {
        val blocks = ReportFormatter.build(input)
        val file = File(context.filesDir, "reports/report_${input.type.name}_${STAMP.format(input.createdAt)}.pdf")
        withContext(Dispatchers.IO) {
            PdfReportWriter.write(blocks, "CR-V Diag · ${input.type.label} · VIN ${input.data.vin ?: "not reported"}", file)
            CrvDatabase.get(context).scanReportDao().insert(
                ScanReportEntity(
                    createdAtEpochMs = input.createdAt.toInstant().toEpochMilli(), type = input.type.label,
                    vin = input.data.vin, fileName = file.name, summary = ReportFormatter.summary(input),
                ),
            )
        }
        return blocks to file
    }

    fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
