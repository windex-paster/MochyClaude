// a module player's panels and drawn shapes: boxes with a fill, a gradient or frosted glass behind their blocks, and shapes from svg path data

package com.mochyy.mochymusic.ui.player.styles

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import com.mochyy.mochymusic.modules.skin.ButtonKind
import com.mochyy.mochymusic.modules.skin.SkinTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.modules.skin.GradientKind
import com.mochyy.mochymusic.modules.skin.SkinBlock
import com.mochyy.mochymusic.ui.component.GlassPillBackground
import kotlin.math.min

@OptIn(ExperimentalFoundationApi::class)
/** a panel: frosted glass, a fill or a gradient behind its blocks, a border, round corners, its blocks in a column or a row */
@Composable
internal fun SkinBox(block: SkinBlock.Box, context: SkinContext, modifier: Modifier, inRow: Boolean) {
    val shape = RoundedCornerShape(block.corner)
    val colors = block.fill.map(context::color)
    val sized = modifier
        .then(block.width?.let { Modifier.width(it) } ?: if (inRow) Modifier else Modifier.fillMaxWidth())
        .then(block.height?.let { Modifier.height(it) } ?: Modifier)
        .then(if (block.shadow) Modifier.shadow(16.dp, shape) else Modifier)
        .clip(shape)
    val glass = context.glass.takeIf { block.glass != null }
    // a tap or a long press on the panel itself; the controls inside answer their own touches first
    val run = rememberTargetRunner(context)
    val pressable = if (block.tap != null || block.hold != null) {
        Modifier.combinedClickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onLongClick = block.hold?.let { target -> { run(target) } },
            onClick = { block.tap?.let(run) },
        )
    } else Modifier
    val pages = (block.up as? SkinTarget.Control)?.kind == ButtonKind.Pages && !context.preview && context.actions.onPageDrag != null
    val swipe = if (pages) null else block.up?.let { target -> { run(target) } }
    // how far the panel has been pulled up, in px: it rises and fades while the next page rises over it
    var lift by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val span = with(density) { PagePullSpan.toPx() }
    val scope = rememberCoroutineScope()
    val pulled = if (pages) {
        Modifier
            .graphicsLayer {
                val hidden = !context.overlay && context.actions.page() != 0
                alpha = if (hidden) 0f else 1f - (lift / span).coerceIn(0f, 1f) * 0.85f
                translationY = -lift * 0.55f
            }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        val commit = total < -span * 0.28f
                        context.actions.onPageRelease?.invoke(commit)
                        // back down smoothly: the page it opened is already carrying on by itself
                        val from = lift
                        scope.launch { androidx.compose.animation.core.animate(from, 0f) { v, _ -> lift = v } }
                    },
                    onDragCancel = {
                        context.actions.onPageRelease?.invoke(false)
                        val from = lift
                        scope.launch { androidx.compose.animation.core.animate(from, 0f) { v, _ -> lift = v } }
                    },
                    onVerticalDrag = { _, dy ->
                        total += dy
                        lift = (-total).coerceAtLeast(0f)
                        context.actions.onPageDrag?.invoke(lift / span)
                    },
                )
            }
    } else Modifier
    Box(sized.then(if (context.preview) Modifier else pressable.onSwipeUp(swipe != null, swipe ?: {}).then(pulled))) {
        if (glass != null) {
            val radius = with(LocalDensity.current) { (block.glass ?: 0f).dp.toPx() }
            // the blur reads past the box's edges and the clip trims it, so nothing bright smears in from an edge
            GlassPillBackground(
                state = glass,
                blurRadius = { radius },
                tint = colors.singleOrNull() ?: Color.Transparent,
                tileMode = android.graphics.Shader.TileMode.CLAMP,
                bleed = true,
                modifier = Modifier.matchParentSize(),
            )
            if (colors.size > 1) Box(Modifier.matchParentSize().background(gradient(colors, block.gradient)))
        } else if (colors.isNotEmpty() || block.glass != null) {
            // over the lyrics or the queue the panel has no glass behind it, so it gets a dark base: the rows under it no longer show through
            if (context.overlay) Box(Modifier.matchParentSize().background(OverlayBase))
            // where there's no glass to be had (the small preview, a tones background) a frosted box still reads as a panel
            val fill = colors.ifEmpty { listOf(GlassStandIn) }
            Box(Modifier.matchParentSize().background(gradient(fill, block.gradient)))
        }
        block.stroke?.let { Box(Modifier.matchParentSize().border(it, context.color(block.strokeColor), shape)) }
        val inner = Modifier
            .then(if (block.height != null) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(block.padding)
        if (block.row) {
            Row(inner, horizontalArrangement = Arrangement.spacedBy(block.gap), verticalAlignment = Alignment.CenterVertically) {
                RowBlocks(block.blocks, context)
            }
        } else {
            Column(inner, verticalArrangement = Arrangement.spacedBy(block.gap), horizontalAlignment = context.layout.align.horizontal) {
                ColumnBlocks(block.blocks, context)
            }
        }
    }
}

/** a swipe up of a good thumb's length that no child took (a slider or a button keeps its own drags) */
internal fun Modifier.onSwipeUp(enabled: Boolean, onUp: () -> Unit): Modifier =
    if (!enabled) this else this.composed {
        // read at the end of the drag, so a recomposition mid-swipe doesn't restart it
        val latest by androidx.compose.runtime.rememberUpdatedState(onUp)
        pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = { if (total < -UpDistance.toPx()) latest() },
                onDragCancel = { total = 0f },
                onVerticalDrag = { _, dy -> total += dy },
            )
        }
    }

private val UpDistance = 56.dp

// how far up the panel is pulled for the next page to be all the way up
private val PagePullSpan = 420.dp

private fun gradient(colors: List<Color>, kind: GradientKind): Brush {
    if (colors.size == 1) return Brush.linearGradient(listOf(colors[0], colors[0]))
    return when (kind) {
        GradientKind.Vertical -> Brush.verticalGradient(colors)
        GradientKind.Horizontal -> Brush.horizontalGradient(colors)
        GradientKind.Diagonal -> Brush.linearGradient(colors, start = Offset.Zero, end = Offset.Infinite)
        GradientKind.Radial -> Brush.radialGradient(colors)
    }
}

/** a shape from svg path data, scaled from its view box into its own box, filled, outlined or both */
@Composable
internal fun SkinVector(block: SkinBlock.Vector, context: SkinContext, modifier: Modifier) {
    // parsed once per shape; a path the parser can't read draws nothing instead of breaking the player
    val path = remember(block.data) { runCatching { PathParser().parsePathString(block.data).toPath() }.getOrNull() }
    val fill = block.fill.map(context::color)
    val outline = block.strokeColor?.let(context::color)
    val width = block.width ?: (block.height * (block.viewWidth / block.viewHeight))
    Spacer(
        modifier
            .size(width, block.height)
            .drawBehind {
                path ?: return@drawBehind
                val sx = size.width / block.viewWidth
                val sy = size.height / block.viewHeight
                scale(sx, sy, pivot = Offset.Zero) {
                    if (fill.isNotEmpty()) {
                        val brush = if (fill.size == 1) Brush.linearGradient(listOf(fill[0], fill[0])) else Brush.verticalGradient(fill, 0f, block.viewHeight)
                        drawPath(path, brush)
                    }
                    if (outline != null && block.stroke != null) {
                        // the stroke is given in dp on screen, so it's drawn thinner in the view box's own units
                        val strokeWidth = block.stroke.toPx() / min(sx, sy).coerceAtLeast(0.001f)
                        drawPath(path, outline, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            },
    )
}

private val GlassStandIn = Color.White.copy(alpha = 0.10f)

/** the dark base under a panel shown over the lyrics or the queue: nearly solid, so the page behind it stays out of sight */
private val OverlayBase = Color(0xF2101014)
