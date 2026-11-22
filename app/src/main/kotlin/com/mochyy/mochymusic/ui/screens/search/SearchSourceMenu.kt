// the round button at the right of the search field and the glass list it opens: where to search (YouTube Music or a source with its own search)

package com.mochyy.mochymusic.ui.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.GlassPillBackground
import com.mochyy.mochymusic.ui.component.GlassState
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.rememberProgressiveBlur

/** one place to search: its name, a line about it and its picture */
internal data class SearchSourceChoice(
    val label: String,
    val description: String,
    val icon: Int,
    /** the logo's own outline: a circle for YouTube Music, a rounded square for the others */
    val round: Boolean = false,
    /** the picture is a tinted glyph of the app's own, not a coloured logo */
    val glyph: Boolean = false,
    /** a module's own picture (the icon its zip brought, or its first letter), drawn instead of [icon] */
    val moduleId: String? = null,
    val art: String? = null,
)

/** a service's own logo, cut to its outline */
@Composable
private fun SourceLogo(choice: SearchSourceChoice, size: androidx.compose.ui.unit.Dp) {
    if (choice.moduleId != null) {
        com.mochyy.mochymusic.ui.screens.settings.modules.ModuleLogo(
            id = choice.moduleId,
            name = choice.label,
            size = size.value.toInt(),
            art = choice.art,
            corner = 0.24f,
        )
        return
    }
    if (choice.glyph) {
        Icon(painter = painterResource(choice.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(size))
        return
    }
    Box(Modifier.size(size).clip(if (choice.round) CircleShape else RoundedCornerShape(size * 0.24f))) {
        Image(
            painter = painterResource(choice.icon),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // the round logo has a little black round it in its picture: scaled a touch so the red reaches the edge
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val k = if (choice.round) 1.07f else 1f
                scaleX = k
                scaleY = k
            },
        )
    }
}

private val MenuShape = RoundedCornerShape(30.dp)
private val MenuWidth = 252.dp

/** the round button: the picture of the place being searched */
@Composable
internal fun SearchSourceButton(current: SearchSourceChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(SearchFieldHeight)
            .clip(CircleShape)
            .background(SearchColors.TileHigh)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = RemoteFocus, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SourceLogo(current, 28.dp)
    }
}

/**
 * The list under the button, drawn over the page in glass: the results blur behind it.
 * [topInset] is where the field ends, so it opens just under the button.
 */
@Composable
internal fun SearchSourceMenu(
    open: Boolean,
    choices: List<SearchSourceChoice>,
    selected: Int,
    glassState: GlassState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    topInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    // a tap anywhere else closes it
    if (open) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    Box(modifier = modifier.fillMaxSize().padding(top = topInset, end = SearchHorizontalPadding), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 0.82f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.86f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            val blur = rememberProgressiveBlur()
            Box(
                modifier = Modifier
                    .width(MenuWidth)
                    .clip(MenuShape)
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.08f))), MenuShape),
            ) {
                if (blur) {
                    GlassPillBackground(
                        state = glassState,
                        blurRadius = { 60f },
                        tint = Color.White.copy(alpha = 0.07f),
                        foundationColor = Color(0xFF0B0B0D).copy(alpha = 0.9f),
                        shape = MenuShape,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Box(Modifier.matchParentSize().background(Color(0xF2141417)))
                }
                // a dark veil over the blur so the page behind never shows through the text
                Box(Modifier.matchParentSize().background(Color(0xFF0B0B0D).copy(alpha = 0.55f)))
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(
                        text = stringResource(R.string.search_where),
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp),
                    )
                    choices.forEachIndexed { index, choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index) }
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            SourceLogo(choice, 30.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = choice.label,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (choice.description.isNotBlank()) {
                                    Text(
                                        text = choice.description,
                                        fontSize = 12.5.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (index == selected) {
                                Icon(
                                    painter = painterResource(R.drawable.check),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
