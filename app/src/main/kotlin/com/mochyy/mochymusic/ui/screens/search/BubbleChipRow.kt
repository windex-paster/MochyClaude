// a row of choices with one white bubble behind the picked one that slides to the next, stretching as it goes, like the highlight in the bottom bar

package com.mochyy.mochymusic.ui.screens.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// the same springs as the bottom bar's highlight: the edge it moves towards is quick, the one it leaves lags, so it stretches and then catches up
private val BubbleLead = spring<Float>(dampingRatio = 0.82f, stiffness = 700f)
private val BubbleTrail = spring<Float>(dampingRatio = 0.82f, stiffness = 320f)

@Composable
fun BubbleChipRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // where each chip sits in the row, measured; NaN until it has been
    val lefts = remember(labels.size) { mutableStateListOf<Float>().apply { repeat(labels.size) { add(Float.NaN) } } }
    val rights = remember(labels.size) { mutableStateListOf<Float>().apply { repeat(labels.size) { add(Float.NaN) } } }

    val bubbleLeft = remember { Animatable(0f) }
    val bubbleRight = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }

    val targetLeft = lefts.getOrNull(selectedIndex) ?: Float.NaN
    val targetRight = rights.getOrNull(selectedIndex) ?: Float.NaN
    LaunchedEffect(selectedIndex, targetLeft, targetRight) {
        if (targetLeft.isNaN() || targetRight.isNaN()) return@LaunchedEffect
        if (!placed) {
            // the first time it just appears on the picked chip rather than sliding in from the edge
            bubbleLeft.snapTo(targetLeft)
            bubbleRight.snapTo(targetRight)
            placed = true
            return@LaunchedEffect
        }
        val movingRight = targetLeft > bubbleLeft.value
        launch { bubbleLeft.animateTo(targetLeft, if (movingRight) BubbleTrail else BubbleLead) }
        bubbleRight.animateTo(targetRight, if (movingRight) BubbleLead else BubbleTrail)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.width(SearchHorizontalPadding))
        Row(
            modifier = Modifier.drawBehind {
                val radius = CornerRadius(size.height / 2f)
                // every chip's dark pill first, then the bubble over the picked one; the labels draw over both
                for (i in lefts.indices) {
                    val left = lefts[i]
                    val right = rights[i]
                    if (left.isNaN() || right.isNaN()) continue
                    drawRoundRect(
                        color = SearchColors.Tile,
                        topLeft = Offset(left, 0f),
                        size = Size(right - left, size.height),
                        cornerRadius = radius,
                    )
                }
                if (placed && selectedIndex in labels.indices) {
                    val left = bubbleLeft.value
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(left, 0f),
                        size = Size((bubbleRight.value - left).coerceAtLeast(0f), size.height),
                        cornerRadius = radius,
                    )
                }
            },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (selected) Color.Black else SearchColors.Primary,
                    animationSpec = tween(200),
                    label = "bubble_chip_text",
                )
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .onPlaced { coordinates ->
                            val left = coordinates.positionInParent().x
                            lefts[index] = left
                            rights[index] = left + coordinates.size.width
                        }
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(index) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = textColor,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(SearchHorizontalPadding))
    }
}
