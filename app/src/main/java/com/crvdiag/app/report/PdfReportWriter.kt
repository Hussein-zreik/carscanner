package com.crvdiag.app.report

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import java.io.File

/** Draws report blocks onto A4 pages (print-friendly: white paper, dark text). */
object PdfReportWriter {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val FOOTER = 28f
    private val CONTENT_W = (PAGE_W - 2 * MARGIN).toInt()

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.rgb(28, 30, 34)) = TextPaint().apply {
        isAntiAlias = true
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.color = color
    }

    private fun toneColor(t: Tone) = when (t) {
        Tone.Normal -> Color.rgb(28, 30, 34)
        Tone.Good -> Color.rgb(18, 120, 60)
        Tone.Warn -> Color.rgb(170, 95, 0)
        Tone.Bad -> Color.rgb(190, 30, 30)
        Tone.Muted -> Color.rgb(100, 106, 115)
    }

    private fun layout(text: String, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

    fun write(blocks: List<Block>, footer: String, file: File) {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun finishPage() {
            page?.let { p ->
                val f = paint(8f, color = toneColor(Tone.Muted))
                p.canvas.drawText("$footer · page $pageNo", MARGIN, PAGE_H - 18f, f)
                doc.finishPage(p)
            }
        }
        fun newPage() {
            finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN
        }
        fun ensure(height: Float) {
            if (page == null || y + height > PAGE_H - MARGIN - FOOTER) newPage()
        }
        fun draw(l: StaticLayout, x: Float) {
            page!!.canvas.withTranslation(x, y) { l.draw(this) }
        }

        for (block in blocks) when (block) {
            is Block.Title -> {
                val t = layout(block.title, paint(20f, bold = true), CONTENT_W)
                val s = layout(block.subtitle, paint(10f, color = toneColor(Tone.Muted)), CONTENT_W)
                ensure(t.height + s.height + 16f)
                draw(t, MARGIN); y += t.height + 4f
                draw(s, MARGIN); y += s.height + 12f
            }
            is Block.Heading -> {
                val h = layout(block.text, paint(13f, bold = true, color = Color.rgb(0, 75, 114)), CONTENT_W)
                ensure(h.height + 40f) // keep a heading with the start of its content
                y += 8f
                draw(h, MARGIN); y += h.height + 6f
            }
            is Block.Text -> {
                val l = layout(block.text, paint(9.5f, color = toneColor(block.tone)), CONTENT_W)
                ensure(l.height + 6f)
                draw(l, MARGIN); y += l.height + 6f
            }
            is Block.Fields -> for (f in block.rows) {
                val labelW = (CONTENT_W * 0.36f).toInt()
                val k = layout(f.label, paint(9.5f, color = toneColor(Tone.Muted)), labelW - 8)
                val v = layout(f.value, paint(9.5f, bold = f.tone == Tone.Bad, color = toneColor(f.tone)), CONTENT_W - labelW)
                val h = maxOf(k.height, v.height).toFloat()
                ensure(h + 4f)
                draw(k, MARGIN); draw(v, MARGIN + labelW); y += h + 4f
            }
            is Block.Table -> {
                val widths = block.weights.map { (it * CONTENT_W).toInt() }
                fun drawRow(cells: List<String>, header: Boolean, tone: Tone) {
                    val ls = cells.mapIndexed { i, c ->
                        layout(c, paint(9f, bold = header, color = if (header) Color.WHITE else toneColor(tone)), widths[i] - 8)
                    }
                    val h = ls.maxOf { it.height } + 8f
                    ensure(h)
                    val c = page!!.canvas
                    val bg = Paint().apply { color = if (header) Color.rgb(0, 75, 114) else Color.rgb(244, 246, 248) }
                    c.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + h, bg)
                    c.drawLine(MARGIN, y + h, MARGIN + CONTENT_W, y + h, Paint().apply { color = Color.rgb(220, 224, 228) })
                    var x = MARGIN + 4f
                    y += 4f
                    ls.forEachIndexed { i, l -> draw(l, x); x += widths[i] }
                    y += h - 4f
                }
                drawRow(block.headers, header = true, tone = Tone.Normal)
                block.rows.forEach { drawRow(it.cells, header = false, tone = it.tone) }
                y += 6f
            }
        }
        finishPage()
        file.parentFile?.mkdirs()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
