package com.crvdiag.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small outline icons not in material-icons-core. Drawn on a 24×24 grid. */
object AppIcons {
    val Gauge: ImageVector by lazy {
        outline("Gauge") {
            // Dial arc, needle and hub.
            moveTo(4f, 17f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 17f)
            moveTo(12f, 17f)
            lineTo(16f, 11f)
            moveTo(11f, 17f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 13f, y1 = 17f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 11f, y1 = 17f)
        }
    }

    /** Spark / lightning bolt (misfire check). */
    val Spark: ImageVector by lazy {
        outline("Spark") {
            moveTo(13f, 2f); lineTo(5f, 13f); lineTo(11f, 13f); lineTo(10f, 22f); lineTo(19f, 10f); lineTo(13f, 10f); close()
        }
    }

    val Plug: ImageVector by lazy {
        outline("Plug") {
            // Two prongs, rounded body, cable.
            moveTo(9f, 3f); lineTo(9f, 7f)
            moveTo(15f, 3f); lineTo(15f, 7f)
            moveTo(6f, 7f); lineTo(18f, 7f); lineTo(18f, 10f)
            arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 13f, y1 = 15f)
            lineTo(11f, 15f)
            arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 6f, y1 = 10f)
            close()
            moveTo(12f, 15f); lineTo(12f, 21f)
        }
    }

    private fun outline(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()
}
