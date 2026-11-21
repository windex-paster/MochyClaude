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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
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
import com.mochyy.mochymusic.module.EntryKind
import com.mochyy.mochymusic.module.SourceEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.mochyy.mochymusic.R

/** where a source's search stands, for the list under the chips */
sealed interface SourceSearchState {
    data object Loading : SourceSearchState
    data class Done(val hits: List<SearchHit>) : SourceSearchState
    data class Failed(val reason: String) : SourceSearchState
}

/** artists, albums or playlists of a source's search */
sealed interface EntryState {
    data object Loading : EntryState
    data class Done(val entries: List<SourceEntry>) : EntryState
    data class Failed(val reason: String) : EntryState
}

@Composable
fun entryKindLabel(kind: EntryKind): String = stringResource(
    when (kind) {
        EntryKind.Artist -> R.string.source_kind_artist
        EntryKind.Album -> R.string.source_kind_album
        EntryKind.Playlist -> R.string.source_kind_playlist
    },
)

/** artists, albums or playlists as rows (an artist's picture round); [limit] cuts the list for the "All" view */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.entryResultItems(
    state: EntryState?,
    keyPrefix: String,
    limit: Int? = null,
    onClick: (SourceEntry) -> Unit,
) {
    when (state) {
        null, EntryState.Loading -> item(key = "${keyPrefix}_loading") {
            Box(modifier = Modifier.fillMaxWidth().height(if (limit == null) 220.dp else 60.dp), contentAlignment = Alignment.Center) {
                SearchLoadingDots()
            }
        }
        is EntryState.Failed -> if (limit == null) item(key = "${keyPrefix}_failed") { Message("Couldn't reach it: ${state.reason}") }
        is EntryState.Done -> {
            val shown = if (limit != null) state.entries.take(limit) else state.entries
            if (shown.isEmpty()) {
                if (limit == null) item(key = "${keyPrefix}_empty") { Message(androidx.compose.ui.res.stringResource(R.string.source_nothing_found)) }
            } else {
                items(shown.size, key = { "${keyPrefix}_${shown[it].id}" }) { index ->
                    val entry = shown[index]
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { onClick(entry) })
                                .padding(horizontal = SearchHorizontalPadding, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SearchArtwork(url = entry.artworkUrl, size = 46.dp, circle = entry.kind == EntryKind.Artist)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                    color = SearchColors.Primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOf(entryKindLabel(entry.kind), entry.subtitle).filter { it.isNotBlank() }.joinToString("  •  "),
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

/** the best match as a card over the list: the picture, the name, and what kind of thing it is */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceTopCard(
    artworkUrl: String?,
    title: String,
    caption: String,
    circle: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SearchHorizontalPadding, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SearchColors.TileHigh)
            .combinedClickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchArtwork(url = artworkUrl, size = 72.dp, circle = circle, corner = 10.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = SearchColors.Primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = SearchColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.sourceResultItems(
    state: SourceSearchState?,
    activeMediaId: String?,
    mediaIdOf: (SearchHit) -> String,
    onClick: (index: Int, hit: SearchHit) -> Unit,
    limit: Int? = null,
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
                val count = if (limit != null) minOf(limit, state.hits.size) else state.hits.size
                items(count, key = { state.hits[it].id }) { index ->
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

/** a section's title with how many there are and an arrow, tapping it opens the whole list (the header of a carousel) */
@Composable
fun SourceSectionHeader(title: String, count: Int?, onMore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onMore)
            .padding(horizontal = SearchHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            color = SearchColors.Primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null && count > 0) {
            Text(text = count.toString(), fontSize = 15.sp, color = SearchColors.Secondary)
            Spacer(Modifier.width(10.dp))
        }
        Icon(
            painter = androidx.compose.ui.res.painterResource(R.drawable.navigate_next),
            contentDescription = null,
            tint = SearchColors.Primary,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** artists (round pictures), albums or playlists (big covers) in one row you swipe sideways */
fun LazyListScope.entryCarouselItem(state: EntryState?, keyPrefix: String, onClick: (SourceEntry) -> Unit) {
    when (state) {
        null, EntryState.Loading -> item(key = "${keyPrefix}_loading") {
            Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { SearchLoadingDots() }
        }
        is EntryState.Failed -> Unit
        is EntryState.Done -> if (state.entries.isNotEmpty()) item(key = "${keyPrefix}_row") {
            val shown = state.entries.take(20)
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SearchHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(shown.size, key = { "${keyPrefix}_${shown[it].id}" }) { index ->
                    val entry = shown[index]
                    val round = entry.kind == EntryKind.Artist
                    val size = if (round) 136.dp else 168.dp
                    Column(
                        modifier = Modifier.width(size).clickable { onClick(entry) },
                        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
                    ) {
                        SearchArtwork(url = entry.artworkUrl, size = size, circle = round, corner = 12.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = entry.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SearchColors.Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = if (round) TextAlign.Center else TextAlign.Start,
                        )
                        if (!round && entry.subtitle.isNotBlank()) {
                            Text(
                                text = entry.subtitle,
                                fontSize = 13.sp,
                                color = SearchColors.Secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
