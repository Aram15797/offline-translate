package com.offline.translator.feature.text

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal enum class LinguaSymbol { Translate, Book, History, Download, Settings }

private fun symbolVector(symbol: LinguaSymbol): ImageVector = ImageVector.Builder(
    name = symbol.name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathFillType = PathFillType.NonZero,
    ) {
        when (symbol) {
            LinguaSymbol.Translate -> {
                moveTo(3f, 7f); lineTo(19f, 7f); lineTo(15f, 3f)
                moveTo(19f, 7f); lineTo(15f, 11f)
                moveTo(21f, 17f); lineTo(5f, 17f); lineTo(9f, 13f)
                moveTo(5f, 17f); lineTo(9f, 21f)
            }
            LinguaSymbol.Book -> {
                moveTo(12f, 5f); curveTo(9f, 3f, 5f, 3f, 2f, 4f)
                lineTo(2f, 19f); curveTo(5f, 18f, 9f, 18f, 12f, 20f)
                curveTo(15f, 18f, 19f, 18f, 22f, 19f)
                lineTo(22f, 4f); curveTo(19f, 3f, 15f, 3f, 12f, 5f)
                lineTo(12f, 20f)
                moveTo(5f, 8f); lineTo(9f, 9f)
                moveTo(15f, 9f); lineTo(19f, 8f)
            }
            LinguaSymbol.History -> {
                moveTo(3f, 10f); curveTo(4f, 4f, 10f, 1f, 16f, 4f)
                curveTo(22f, 7f, 22f, 16f, 17f, 20f)
                curveTo(12f, 23f, 5f, 21f, 3f, 16f)
                moveTo(2f, 4f); lineTo(3f, 10f); lineTo(8f, 9f)
                moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
            }
            LinguaSymbol.Settings -> {
                moveTo(3f, 6f); lineTo(7f, 6f)
                moveTo(13f, 6f); lineTo(21f, 6f)
                moveTo(3f, 18f); lineTo(7f, 18f)
                moveTo(13f, 18f); lineTo(21f, 18f)
                moveTo(3f, 12f); lineTo(13f, 12f)
                moveTo(19f, 12f); lineTo(21f, 12f)
                moveTo(10f, 3f); curveTo(14f, 3f, 14f, 9f, 10f, 9f)
                curveTo(6f, 9f, 6f, 3f, 10f, 3f)
                moveTo(16f, 9f); curveTo(20f, 9f, 20f, 15f, 16f, 15f)
                curveTo(12f, 15f, 12f, 9f, 16f, 9f)
                moveTo(10f, 15f); curveTo(14f, 15f, 14f, 21f, 10f, 21f)
                curveTo(6f, 21f, 6f, 15f, 10f, 15f)
            }
            LinguaSymbol.Download -> {
                moveTo(12f, 3f); lineTo(12f, 15f)
                moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
                moveTo(4f, 16f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 16f)
            }
        }
    }
}.build()

private val symbols = LinguaSymbol.entries.associateWith(::symbolVector)

@Composable
internal fun LinguaIcon(symbol: LinguaSymbol, modifier: Modifier = Modifier) {
    Icon(symbols.getValue(symbol), contentDescription = null, modifier = modifier)
}
