// the search page's start: where to search (small logos), what was played last, the last searches and the genres

package com.mochyy.mochymusic.ui.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.modules.SourceModules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** how many of the last played tracks the start page shows */
internal const val HomeRecentTracks = 7

/** how many of the last searches show before "all history" is opened */
internal const val HomeHistoryShown = 5

/** how many genre tiles show before "more" is pressed */
internal const val HomeGenresShown = 6

/** how many places to search fit across the page; the others are further to the right */
private const val SourcesAcross = 4

/** a place to search, as a small tile; [id] is null for YouTube Music */
internal data class HomeSource(val id: String?, val choice: SearchSourceChoice)

@HiltViewModel
class SearchHomeViewModel
@Inject
constructor(
    private val sourceModules: SourceModules,
    database: MusicDatabase,
) : ViewModel() {
    // YouTube Music first, then every module with a search of its own; follows modules being switched on and off
    internal val sources: StateFlow<List<HomeSource>> = sourceModules.entries
        .map { build() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, build())

    val recentSongs: StateFlow<List<Song>> = database.recentlyPlayedSongs(HomeRecentTracks)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<com.mochyy.mochymusic.db.entities.SearchHistory>> = database.searchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun build(): List<HomeSource> {
        val entries = sourceModules.entries.value
        val modules = sourceModules.searchable().map { module ->
            val id = module.info.id
            val entry = entries.firstOrNull { it.id == id }
            HomeSource(
                id = id,
                choice = SearchSourceChoice(
                    label = entry?.customName ?: module.searchLabel,
                    description = "",
                    icon = when (id) {
                        "soundcloud" -> R.drawable.source_logo_soundcloud
                        "yandex" -> R.drawable.source_logo_yandex
                        else -> R.drawable.globe_search
                    },
                    moduleId = id.takeIf { it != "soundcloud" && it != "yandex" },
                    art = entry?.icon,
                ),
            )
        }
        return listOf(HomeSource(null, SearchSourceChoice("YouTube Music", "", R.drawable.source_logo_ytmusic, round = true))) + modules
    }
}

/**
 * The places to search, four across so they fill the page exactly; more than four continue to the right
 * and snap into place a page at a time.
 */
@Composable
internal fun HomeSources(
    sources: List<HomeSource>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val state = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = SearchHorizontalPadding
        val tile = (maxWidth - side * 2) / SourcesAcross
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(horizontal = side),
        ) {
            items(sources, key = { it.id ?: "youtube" }) { source ->
                val on = source.id == selected
                Column(
                    modifier = Modifier
                        .width(tile)
                        .padding(horizontal = 3.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .then(if (on) Modifier.border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(18.dp)) else Modifier)
                        .clickable { onSelect(source.id) }
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HomeSourceLogo(source.choice, 40.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = source.choice.label,
                        fontSize = 11.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Color.White else SearchColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSourceLogo(choice: SearchSourceChoice, size: androidx.compose.ui.unit.Dp) {
    // the same logo the results page's menu draws
    SearchSourceLogo(choice, size)
}

/** the last played tracks as big square covers, side by side; a tap plays it */
@Composable
internal fun HomeRecentTracks(
    songs: List<Song>,
    onPlay: (Int) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = SearchHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(songs.size, key = { songs[it].id }) { index ->
            val song = songs[index]
            Column(
                modifier = Modifier
                    .width(148.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPlay(index) },
            ) {
                SearchArtwork(url = song.thumbnailUrl, size = 148.dp, corner = 14.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = song.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SearchColors.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artists.joinToString { it.name },
                    fontSize = 12.sp,
                    color = SearchColors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** the two buttons under the last searches: clear everything, and open or close the whole list */
@Composable
internal fun HomeHistoryButtons(
    open: Boolean,
    canOpen: Boolean,
    onClear: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SearchHorizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomePill(androidx.compose.ui.res.stringResource(R.string.search_history_clear_all), onClear, Modifier.weight(1f))
        if (canOpen) {
            HomePill(
                androidx.compose.ui.res.stringResource(if (open) R.string.search_history_fewer else R.string.search_history_all),
                onToggle,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HomePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(SearchColors.TileHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SearchColors.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** content that opens and closes smoothly (the rest of the history) */
@Composable
internal fun HomeExpandable(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(tween(280)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(240)) + fadeOut(tween(160)),
    ) {
        content()
    }
}

/** the "more" button under the first genres */
@Composable
internal fun HomeMoreButton(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = SearchHorizontalPadding, vertical = 12.dp)) {
        HomePill(androidx.compose.ui.res.stringResource(R.string.search_genres_more), onClick, Modifier.fillMaxWidth())
    }
}
