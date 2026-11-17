package com.crvdiag.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter

class CsvWriterTest {
    @Test
    fun `header, rows, BOM and escaping`() {
        val out = StringWriter()
        CsvWriter(out, listOf("time", "Coolant temperature (°C)", "Fuel system")).use { csv ->
            csv.row(listOf("15:30:00.000", "85", "Closed loop (normal)"))
            csv.row(listOf("15:30:00.250", null, "Open loop: load, \"decel\""))
        }
        assertEquals(
            "\uFEFFtime,Coolant temperature (°C),Fuel system\r\n" +
                "15:30:00.000,85,Closed loop (normal)\r\n" +
                "15:30:00.250,,\"Open loop: load, \"\"decel\"\"\"\r\n",
            out.toString(),
        )
    }
}
