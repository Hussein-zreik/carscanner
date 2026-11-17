package com.crvdiag.data

import java.io.Writer

/**
 * Minimal RFC 4180 CSV writer. Starts with a UTF-8 byte-order mark so Excel shows "°C"
 * correctly. Values containing commas, quotes or line breaks are quoted.
 */
class CsvWriter(private val out: Writer, columns: List<String>) : AutoCloseable {
    init {
        out.write(BYTE_ORDER_MARK)
        writeLine(columns)
    }

    fun row(values: List<String?>) = writeLine(values.map { it.orEmpty() })

    fun flush() = out.flush()

    override fun close() = out.close()

    private fun writeLine(cells: List<String>) {
        out.write(cells.joinToString(",") { escape(it) })
        out.write("\r\n")
    }

    companion object {
        /** U+FEFF, written as a char code so the source file itself stays BOM-free. */
        private const val BYTE_ORDER_MARK = 0xFEFF

        fun escape(cell: String): String =
            if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\""
            else cell
    }
}
