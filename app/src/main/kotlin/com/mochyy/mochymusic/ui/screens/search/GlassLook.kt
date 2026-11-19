// the glass look of the search page's chips and field: a pale gradient with a bright edge, no blur needed

package com.mochyy.mochymusic.ui.screens.search

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** a frosted pill at [left], [top]; [bright] is the picked one, a milky white instead of a faint tint */
internal fun DrawScope.glassPill(left: Float, top: Float, width: Float, height: Float, bright: Boolean) {
    if (width <= 0f || height <= 0f) return
    val radius = CornerRadius(height / 2f)
    val origin = Offset(left, top)
    val size = Size(width, height)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = if (bright) listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.68f))
            else listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.06f)),
            startY = top,
            endY = top + height,
        ),
        topLeft = origin,
        size = size,
        cornerRadius = radius,
    )
    // the lit edge: bright along the top, fading to almost nothing at the bottom
    val stroke = density
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = if (bright) 0.9f else 0.5f), Color.White.copy(alpha = 0.08f)),
            startY = top,
            endY = top + height,
        ),
        topLeft = origin + Offset(stroke / 2f, stroke / 2f),
        size = Size(width - stroke, height - stroke),
        cornerRadius = CornerRadius((height - stroke) / 2f),
        style = Stroke(width = stroke),
    )
}

/** the search field's own background in glass, drawn the size of whatever it's put on */
internal fun Modifier.glassFill(): Modifier = drawBehind { glassPill(0f, 0f, size.width, size.height, bright = false) }
