// the round button at the right of the search field and the glass list it opens: where to search (YouTube Music or a source with its own search)

package com.mochyy.mochymusic.ui.screens.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.RemoteFocus

/** one place to search: its name, a line about it and its picture */
internal data class SearchSourceChoice(val label: String, val description: String, val icon: Int)

private val MenuShape = RoundedCornerShape(28.dp)

/** the glass look of the open list: a dark tint with a light rim at the top, like the player's panels */
private val MenuTint = Brush.verticalGradient(listOf(Color(0xF02A2A2E), Color(0xF0151518)))

@Composable
internal fun SearchSourceButton(
    choices: List<SearchSourceChoice>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = choices.getOrNull(selected) ?: return
    Box(modifier) {
        Box(
            modifier = Modifier
                .size(SearchFieldHeight)
                .clip(CircleShape)
                .background(SearchColors.TileHigh)
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = RemoteFocus) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(current.icon),
                contentDescription = stringResource(R.string.search_where),
                tint = SearchColors.Primary,
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 8.dp),
            shape = MenuShape,
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 320.dp)
                    .background(MenuTint)
                    .padding(vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.search_where),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                )
                choices.forEachIndexed { index, choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { open = false; onSelect(index) }
                            .padding(horizontal = 22.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            painter = painterResource(choice.icon),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = choice.label,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (choice.description.isNotBlank()) {
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    text = choice.description,
                                    fontSize = 13.sp,
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
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
