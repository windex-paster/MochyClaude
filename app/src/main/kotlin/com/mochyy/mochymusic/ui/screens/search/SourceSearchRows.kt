// the search page's list for a source of its own (SoundCloud): its songs as rows, the same look as YouTube's, played straight from that source

package com.mochyy.mochymusic.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.module.SearchHit

/** where a source's search stands, for the list under the chips */
sealed interface SourceSearchState {
    data object Loading : SourceSearchState
    data class Done(val hits: List<SearchHit>) : SourceSearchState
    data class Failed(val reason: String) : SourceSearchState
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.sourceResultItems(
    state: SourceSearchState?,
    activeMediaId: String?,
    mediaIdOf: (SearchHit) -> String,
    onClick: (index: Int, hit: SearchHit) -> Unit,
) {
    when (state) {
        null, SourceSearchState.Loading -> item(key = "source_loading") {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                SearchLoadingDots()
            }
        }

        is SourceSearchState.Failed -> item(key = "source_failed") {
            Message("Couldn't reach it: ${state.reason}")
        }

        is SourceSearchState.Done -> {
            if (state.hits.isEmpty()) {
                item(key = "source_empty") { Message("Nothing found") }
            } else {
                items(state.hits.size, key = { state.hits[it].id }) { index ->
                    val hit = state.hits[index]
                    val active = mediaIdOf(hit) == activeMediaId
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { onClick(index, hit) })
                                .padding(horizontal = SearchHorizontalPadding, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SearchArtwork(url = hit.artworkUrl, size = 46.dp)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                Text(
                                    text = hit.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = if (active) Color(0xFF7FD1FF) else SearchColors.Primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(
                                        hit.artist.takeIf { it.isNotBlank() },
                                        hit.durationMs?.let { clock(it / 1000L) },
                                    ).joinToString("  •  "),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = SearchColors.Secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        SearchRule()
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxWidth().height(160.dp).padding(horizontal = SearchHorizontalPadding), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = SearchColors.Secondary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun clock(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
