// OnlineSearchResult.kt

package com.mochyy.mochymusic.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import com.mochyy.mochymusic.ui.utils.stableStatusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import com.mochyy.mochymusic.ui.utils.isWideLayout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.os.Build
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.music.innertube.YouTube
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.PauseSearchHistoryKey
import com.mochyy.mochymusic.constants.ProfilePicUriKey
import com.mochyy.mochymusic.db.entities.SearchHistory
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.extensions.togglePlayPause
import com.mochyy.mochymusic.models.toMediaMetadata
import com.mochyy.mochymusic.playback.queues.YouTubeQueue
import com.mochyy.mochymusic.modules.BrowsedIds
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.GlassState
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.component.RoundedHighlight
import com.mochyy.mochymusic.ui.component.SongSwipeBox
import com.mochyy.mochymusic.ui.component.librarySwipeAction
import com.mochyy.mochymusic.ui.component.queueSwipeAction
import com.mochyy.mochymusic.ui.component.glassRoot
import com.mochyy.mochymusic.ui.menu.YouTubeAlbumMenu
import com.mochyy.mochymusic.ui.menu.YouTubeArtistMenu
import com.mochyy.mochymusic.ui.menu.YouTubePlaylistMenu
import com.mochyy.mochymusic.ui.menu.YouTubeSongMenu
import com.mochyy.mochymusic.ui.utils.resize
import com.mochyy.mochymusic.utils.rememberPreference
import com.mochyy.mochymusic.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.mochyy.mochymusic.ui.component.navigateToTab
import com.mochyy.mochymusic.ui.component.RemoteFocus

private val SearchCategories = listOf(
    R.string.search_category_all to null,
    R.string.search_category_music to FILTER_SONG,
    R.string.search_category_artist to FILTER_ARTIST,
    R.string.search_category_video to FILTER_VIDEO,
    R.string.search_category_album to FILTER_ALBUM,
    R.string.search_category_playlist to FILTER_COMMUNITY_PLAYLIST,
    R.string.search_category_featured to FILTER_FEATURED_PLAYLIST,
)

@Composable
fun OnlineSearchResult(
    navController: NavController,
    viewModel: OnlineSearchViewModel = hiltViewModel(),
    pureBlack: Boolean = false,
) {
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)
    val profilePicStr by rememberPreference(ProfilePicUriKey, defaultValue = "")

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val searchFilter by viewModel.filter.collectAsState()
    // null is YouTube Music, otherwise the id of a source with its own search (SoundCloud)
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(sourceId) { sourceId?.let(viewModel::loadSource) }
    // 0 is "All", 1 the songs, then the module's kinds (artists, albums, playlists)
    var sourceCat by rememberSaveable { mutableStateOf(0) }
    val activeKinds = sourceId?.let { viewModel.source(it)?.entryKinds }.orEmpty()
    // how many each chip holds, shown after its name once the results are in
    val trackCount = sourceId?.let { (viewModel.sourceStates[it] as? SourceSearchState.Done)?.hits?.size }
    fun withCount(label: String, n: Int?) = if (n != null && n > 0) "$label $n" else label
    LaunchedEffect(sourceId, sourceCat) {
        val id = sourceId ?: return@LaunchedEffect
        if (sourceCat == 0) activeKinds.forEach { viewModel.loadEntries(id, it) }
        else activeKinds.getOrNull(sourceCat - 2)?.let { viewModel.loadEntries(id, it) }
    }
    val summary = viewModel.summaryPage
    val itemsPage by remember(searchFilter) {
        derivedStateOf { searchFilter?.value?.let { viewModel.viewStateMap[it] } }
    }

    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(viewModel.query, TextRange(viewModel.query.length)))
    }

    val listState = rememberLazyListState()
    val glassState = remember { GlassState() }
    val collapse = rememberCollapseProgress(listState)
    val progressProvider = remember(collapse) { { collapse.value } }

    val selectedIndex = remember(searchFilter) {
        SearchCategories.indexOfFirst { it.second?.value == searchFilter?.value }.coerceAtLeast(0)
    }

    LaunchedEffect(searchFilter) { listState.scrollToItem(0) }

    LaunchedEffect(listState, itemsPage) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 4
        }.collect { nearEnd ->
            if (nearEnd && itemsPage?.continuation != null) viewModel.loadMore()
        }
    }

    BackHandler { navController.navigateUp() }

    val submit: (String) -> Unit = remember(navController, pauseSearchHistory) {
        { raw ->
            val text = raw.trim()
            if (text.isNotEmpty() && text != viewModel.query) {
                focusManager.clearFocus()
                navController.navigate(searchRoute(text)) {
                    popUpTo(searchRoute(viewModel.query)) {
                        inclusive = true
                    }
                }
                if (!pauseSearchHistory) {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.query { insert(SearchHistory(query = text)) }
                    }
                }
            }
        }
    }

    val recentContext = androidx.compose.ui.platform.LocalContext.current
    val onItemClick: (YTItem) -> Unit = { item ->
        // kept for the search page's recent searches
        RecentSearches.add(recentContext, item)
        when (item) {
            is SongItem -> {
                if (item.id == mediaMetadata?.id) {
                    playerConnection.player.togglePlayPause()
                } else {
                    playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                }
            }

            is AlbumItem -> navController.navigate("album/${item.id}")
            is ArtistItem -> navController.navigate("artist/${item.id}")
            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
        }
    }

    val onItemLongClick: (YTItem) -> Unit = { item ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        menuState.show {
            when (item) {
                is SongItem -> YouTubeSongMenu(item, navController, menuState::dismiss)
                is AlbumItem -> YouTubeAlbumMenu(item, navController, menuState::dismiss)
                is ArtistItem -> YouTubeArtistMenu(item, menuState::dismiss)
                is PlaylistItem -> YouTubePlaylistMenu(
                    playlist = item,
                    coroutineScope = coroutineScope,
                    onDismiss = menuState::dismiss,
                )
            }
        }
    }

    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    // on its side the field is in the pane at the left (SearchSidePane): only the kinds of result sit over the list
    val landscape = com.mochyy.mochymusic.ui.utils.isLandscapeLayout()
    val statusTop = WindowInsets.stableStatusBars.asPaddingValues().calculateTopPadding()

    // a second row of chips (the kinds of result) sits under the sources while YouTube Music is picked, and the list has to start under both
    val twoChipRows = viewModel.sources.isNotEmpty() && (sourceId == null || activeKinds.isNotEmpty())
    val extraChipRow = if (twoChipRows) 34.dp else 0.dp
    // in portrait the sources are a list behind the round button, so only the kinds of result are left as chips
    val portraitChips = sourceId == null || activeKinds.isNotEmpty()

    val top = remember(summary, viewModel.query) {
        summary?.let { pickTopResult(it.summaries.flatMap { section -> section.items }, viewModel.query) }
    }

    // where to search: YouTube Music, then each source with a search of its own, in a list behind the round button
    var sourceMenu by rememberSaveable { mutableStateOf(false) }
    val sourceIndexNow = sourceId?.let { id -> viewModel.sources.indexOfFirst { it.info.id == id } }?.takeIf { it >= 0 } ?: -1
    val ytDesc = stringResource(R.string.search_src_youtube_desc)
    val scDesc = stringResource(R.string.search_src_soundcloud_desc)
    val yaDesc = stringResource(R.string.search_src_yandex_desc)
    val sourceChoices = listOf(SearchSourceChoice("YouTube Music", ytDesc, R.drawable.source_logo_ytmusic, round = true)) +
        viewModel.sources.map { module ->
            SearchSourceChoice(
                label = module.searchLabel,
                description = when (module.info.id) {
                    "soundcloud" -> scDesc
                    "yandex" -> yaDesc
                    else -> module.info.description.take(40)
                },
                icon = when (module.info.id) {
                    "soundcloud" -> R.drawable.source_logo_soundcloud
                    "yandex" -> R.drawable.source_logo_yandex
                    else -> R.drawable.globe_search
                },
                glyph = false,
                moduleId = module.info.id.takeIf { it != "soundcloud" && it != "yandex" },
                art = viewModel.artOf(module.info.id),
            )
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SearchColors.page(pureBlack))
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // recorded while scrolling too, so the bar's blur never drops out mid-scroll
                .glassRoot(glassState, isActive = { progressProvider() > 0.01f })
        ) {
            // wide screens show the results two to a row, so the rows aren't mostly empty space
            val columns = if (maxWidth >= 600.dp) 2 else 1
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = if (landscape) statusTop + SearchTopClearance + 34.dp + 18.dp + extraChipRow else searchTopBarHeight(withTitle = false, extra = if (portraitChips) 46.dp else 0.dp),
                    bottom = bottomInset + 24.dp,
                ),
            ) {
                val activeSource = sourceId
                if (activeSource != null) {
                    val module = viewModel.source(activeSource)
                    val openEntry: (com.mochyy.mochymusic.module.SourceEntry) -> Unit = { e ->
                        navController.navigate(
                            "source_entry/$activeSource/${e.kind.name}/${android.net.Uri.encode(e.id)}/${android.net.Uri.encode(e.title.ifBlank { "-" })}",
                        )
                    }
                    val playHit: (Int, com.mochyy.mochymusic.module.SearchHit) -> Unit = { index, hit ->
                        val done = viewModel.sourceStates[activeSource] as? SourceSearchState.Done
                        if (module != null && done != null) {
                            if (BrowsedIds.mediaId(activeSource, hit.id) == mediaMetadata?.id) {
                                playerConnection.player.togglePlayPause()
                            } else {
                                // the results play as a queue from the one pressed, each asked of its own source by its id
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = module.searchLabel,
                                        items = done.hits.map { BrowsedIds.metadataOf(activeSource, it).toMediaItem() },
                                        startIndex = index,
                                    ),
                                )
                            }
                        }
                    }
                    val chosenKind = if (sourceCat >= 2) activeKinds.getOrNull(sourceCat - 2) else null
                    if (chosenKind != null) {
                        entryResultItems(viewModel.entryStates[viewModel.entryKey(activeSource, chosenKind)], "entries_${chosenKind.name}", null, openEntry)
                    } else if (sourceCat == 0 && activeKinds.isNotEmpty()) {
                        val tracks = (viewModel.sourceStates[activeSource] as? SourceSearchState.Done)?.hits.orEmpty()
                        val artists = (viewModel.entryStates[viewModel.entryKey(activeSource, com.mochyy.mochymusic.module.EntryKind.Artist)] as? EntryState.Done)?.entries.orEmpty()
                        val words = viewModel.query.trim().lowercase()
                        val topArtist = artists.firstOrNull()?.takeIf { it.title.lowercase().let { t -> t == words || words in t } }
                        val topTrack = tracks.firstOrNull()
                        if (topArtist != null) {
                            item(key = "source_top") {
                                SourceTopCard(topArtist.artworkUrl, topArtist.title, entryKindLabel(topArtist.kind), circle = true, onClick = { openEntry(topArtist) })
                            }
                        } else if (topTrack != null) {
                            item(key = "source_top") {
                                SourceTopCard(
                                    topTrack.artworkUrl, topTrack.title,
                                    listOf(stringResource(R.string.source_kind_track), topTrack.artist).filter { it.isNotBlank() }.joinToString("  •  "),
                                    circle = false, onClick = { playHit(0, topTrack) },
                                )
                            }
                        }
                        item(key = "source_h_tracks") { SourceSectionHeader(stringResource(R.string.source_cat_tracks), tracks.size) { sourceCat = 1 } }
                        sourceResultItems(
                            state = viewModel.sourceStates[activeSource],
                            activeMediaId = mediaMetadata?.id,
                            mediaIdOf = { hit -> BrowsedIds.mediaId(activeSource, hit.id) },
                            onClick = playHit,
                            limit = 5,
                        )
                        activeKinds.forEach { kind ->
                            val st = viewModel.entryStates[viewModel.entryKey(activeSource, kind)]
                            if (st is EntryState.Done && st.entries.isEmpty()) return@forEach
                            if (st is EntryState.Failed) return@forEach
                            item(key = "source_h_${kind.name}") {
                                SourceSectionHeader(
                                    title = stringResource(
                                        when (kind) {
                                            com.mochyy.mochymusic.module.EntryKind.Artist -> R.string.source_cat_artists
                                            com.mochyy.mochymusic.module.EntryKind.Album -> R.string.source_cat_albums
                                            com.mochyy.mochymusic.module.EntryKind.Playlist -> R.string.source_cat_playlists
                                        },
                                    ),
                                    count = (st as? EntryState.Done)?.entries?.size,
                                    onMore = { sourceCat = 2 + activeKinds.indexOf(kind) },
                                )
                            }
                            entryCarouselItem(st, "all_${kind.name}", openEntry)
                        }
                    } else {
                        sourceResultItems(
                            state = viewModel.sourceStates[activeSource],
                            activeMediaId = mediaMetadata?.id,
                            mediaIdOf = { hit -> BrowsedIds.mediaId(activeSource, hit.id) },
                            onClick = playHit,
                        )
                    }
                } else if (searchFilter == null) {

                    val page = summary
                    if (page == null) {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                SearchLoadingDots()
                            }
                        }
                    } else {
                        if (top != null) {
                            item(key = "top_header") {
                                SearchSectionHeader(title = stringResource(R.string.search_top_results), ruleAbove = false)
                            }
                            item(key = "top_card") {
                                val active = mediaMetadata?.id == top.id
                                TopResultCard(
                                    item = top,
                                    isActive = active,
                                    isPlaying = active && isPlaying,
                                    onClick = { onItemClick(top) },
                                    onLongClick = { onItemLongClick(top) },
                                )
                                Spacer(modifier = Modifier.height(22.dp))
                            }
                        }

                        var renderedSection = false
                        page.summaries.forEachIndexed { index, section ->
                            val rows = section.items.filter { it.id != top?.id }
                            if (rows.isNotEmpty()) {
                                // a row's own rule already closes the previous section
                                val ruleAbove = !renderedSection
                                renderedSection = true
                                item(key = "summary_header_${index}_${section.title}") {
                                    SearchSectionHeader(
                                        title = if (index == 0) stringResource(R.string.search_exact_matches) else section.title,
                                        ruleAbove = ruleAbove,
                                    )
                                }
                                val chunked = rows.chunked(columns)
                                items(
                                    items = chunked,
                                    key = { chunk -> "c${columns}_summary_${index}_${chunk.first().id}" },
                                    contentType = { "resultRow" },
                                ) { rowItems ->
                                    ResultRowChunk(
                                        items = rowItems,
                                        columns = columns,
                                        mediaMetadataId = mediaMetadata?.id,
                                        onItemClick = onItemClick,
                                        onItemLongClick = onItemLongClick,
                                    )
                                }
                                item(key = "summary_gap_$index") {
                                    Spacer(modifier = Modifier.height(18.dp))
                                }
                            }
                        }
                    }
                } else {
                    val page = itemsPage
                    if (page == null) {
                        item(key = "filter_loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                SearchLoadingDots()
                            }
                        }
                    } else if (page.items.isEmpty()) {
                        item(key = "filter_empty") {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.search_no_results),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                    color = SearchColors.Secondary,
                                )
                            }
                        }
                    } else {
                        val chunked = page.items.chunked(columns)
                        items(
                            items = chunked,
                            key = { chunk -> "c${columns}_${chunk.first().id}" },
                            contentType = { "resultRow" },
                        ) { rowItems ->
                            ResultRowChunk(
                                items = rowItems,
                                columns = columns,
                                mediaMetadataId = mediaMetadata?.id,
                                onItemClick = onItemClick,
                                onItemLongClick = onItemLongClick,
                            )
                        }
                    }
                }
            }
        }

        if (landscape) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to SearchColors.page(pureBlack),
                            0.7f to SearchColors.page(pureBlack).copy(alpha = 0.9f),
                            1f to Color.Transparent,
                        )
                    )
                    .padding(top = statusTop + SearchTopClearance - 4.dp, bottom = 14.dp),
            ) {
                SourceAndCategoryRows(
                    sourceLabels = viewModel.sources.map { it.searchLabel },
                    sourceIndex = sourceId?.let { id -> viewModel.sources.indexOfFirst { it.info.id == id } }?.takeIf { it >= 0 },
                    onSource = { index -> sourceCat = 0; sourceId = index?.let { viewModel.sources[it].info.id } },
                    selectedIndex = selectedIndex,
                    onCategory = { index -> viewModel.filter.value = SearchCategories[index].second },
                    sourceCategories = if (activeKinds.isEmpty()) emptyList() else listOf(
                        stringResource(R.string.source_cat_all),
                        withCount(stringResource(R.string.source_cat_tracks), trackCount),
                    ) + activeKinds.map { kind ->
                        withCount(stringResource(
                            when (kind) {
                                com.mochyy.mochymusic.module.EntryKind.Artist -> R.string.source_cat_artists
                                com.mochyy.mochymusic.module.EntryKind.Album -> R.string.source_cat_albums
                                com.mochyy.mochymusic.module.EntryKind.Playlist -> R.string.source_cat_playlists
                            },
                        ), sourceId?.let { (viewModel.entryStates[viewModel.entryKey(it, kind)] as? EntryState.Done)?.entries?.size })
                    },
                    sourceCategory = sourceCat,
                    onSourceCategory = { sourceCat = it },
                )
            }
        } else SearchGlassTopBar(
            glassState = glassState,
            progressProvider = progressProvider,
            pureBlack = pureBlack,
            title = null,
            collapseBelow = true,
            trailing = if (sourceChoices.size < 2) null else {
                {
                    SearchSourceButton(
                        current = sourceChoices[sourceIndexNow + 1],
                        onClick = { sourceMenu = true },
                    )
                }
            },
            below = if (!portraitChips) null else {
                {
                    SourceAndCategoryRows(
                        sourceLabels = emptyList(),
                        sourceIndex = sourceId?.let { id -> viewModel.sources.indexOfFirst { it.info.id == id } }?.takeIf { it >= 0 },
                        onSource = {},
                        selectedIndex = selectedIndex,
                        onCategory = { index -> viewModel.filter.value = SearchCategories[index].second },
                        sourceCategories = if (activeKinds.isEmpty()) emptyList() else listOf(
                            stringResource(R.string.source_cat_all),
                            withCount(stringResource(R.string.source_cat_tracks), trackCount),
                        ) + activeKinds.map { kind ->
                            withCount(stringResource(
                                when (kind) {
                                    com.mochyy.mochymusic.module.EntryKind.Artist -> R.string.source_cat_artists
                                    com.mochyy.mochymusic.module.EntryKind.Album -> R.string.source_cat_albums
                                    com.mochyy.mochymusic.module.EntryKind.Playlist -> R.string.source_cat_playlists
                                },
                            ), sourceId?.let { (viewModel.entryStates[viewModel.entryKey(it, kind)] as? EntryState.Done)?.entries?.size })
                        },
                        sourceCategory = sourceCat,
                        onSourceCategory = { sourceCat = it },
                    )
                }
            },
        ) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                onSearch = submit,
                placeholder = stringResource(R.string.search_placeholder_tracks_albums),
                leading = {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        tint = SearchColors.Secondary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = RemoteFocus,
                            ) { navController.navigateUp() },
                    )
                },
            )
        }
        if (!landscape && sourceChoices.size > 1) {
            SearchSourceMenu(
                open = sourceMenu,
                choices = sourceChoices,
                selected = sourceIndexNow + 1,
                glassState = glassState,
                onSelect = { index ->
                    sourceMenu = false
                    sourceCat = 0
                    sourceId = if (index == 0) null else viewModel.sources[index - 1].info.id
                },
                onDismiss = { sourceMenu = false },
                topInset = statusTop + SearchTopClearance + SearchFieldHeight + 8.dp,
            )
        }
    }
}

/**
 * The chips over the results: where to search (YouTube Music, then each source with its own search),
 * and under them the kinds of YouTube result, which only YouTube has.
 */
@Composable
private fun SourceAndCategoryRows(
    sourceLabels: List<String>,
    sourceIndex: Int?,
    onSource: (Int?) -> Unit,
    selectedIndex: Int,
    onCategory: (Int) -> Unit,
    sourceCategories: List<String> = emptyList(),
    sourceCategory: Int = 0,
    onSourceCategory: (Int) -> Unit = {},
) {
    Column {
        if (sourceLabels.isNotEmpty()) {
            BubbleChipRow(
                labels = listOf("YouTube Music") + sourceLabels,
                selectedIndex = (sourceIndex ?: -1) + 1,
                onSelect = { index -> onSource(if (index == 0) null else index - 1) },
            )
        }
        if (sourceIndex != null && sourceCategories.isNotEmpty()) {
            if (sourceLabels.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
            BubbleChipRow(labels = sourceCategories, selectedIndex = sourceCategory, onSelect = onSourceCategory)
        }
        if (sourceIndex == null) {
            if (sourceLabels.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
            BubbleChipRow(
                labels = SearchCategories.map { stringResource(it.first) },
                selectedIndex = selectedIndex,
                onSelect = onCategory,
            )
        }
    }
}

// youtube's own first pick, unless another result matches the typed words clearly better; taking the first song anywhere showed songs nobody searched for
private fun pickTopResult(items: List<YTItem>, query: String): YTItem? {
    if (items.isEmpty()) return null
    val words = searchWords(query)
    if (words.isEmpty()) return items.first()
    val typed = words.joinToString(" ")

    items.filterIsInstance<ArtistItem>()
        .firstOrNull { searchWords(it.title).joinToString(" ") == typed }
        ?.let { return it }

    // the highest score wins, and on a tie the one youtube put first
    var best: YTItem? = null
    var bestScore = 0
    items.forEach { item ->
        val score = matchScore(item, words, typed)
        if (score > bestScore) {
            best = item
            bestScore = score
        }
    }
    return best ?: items.first()
}

// how well an item's title and credits cover the typed words: all of them beats some, the exact title beats both, and a music video gives way to the song
private fun matchScore(item: YTItem, words: List<String>, typed: String): Int {
    val title = searchWords(item.title)
    val credits = when (item) {
        is SongItem -> item.artists.flatMap { searchWords(it.name) }
        is AlbumItem -> item.artists.orEmpty().flatMap { searchWords(it.name) }
        is PlaylistItem -> item.author?.name?.let(::searchWords).orEmpty()
        else -> emptyList()
    }
    val known = (title + credits).toHashSet()
    val found = words.count { it in known }
    if (found == 0) return 0
    var score = found * 100 / words.size
    if (title.joinToString(" ") == typed) score += 100 else if (found == words.size) score += 40
    if (item is SongItem && item.isVideoSong) score -= 5
    return score.coerceAtLeast(1)
}

// lowercase words without accents, so "Beyoncé" and "beyonce" match
private fun searchWords(text: String): List<String> =
    AccentMarks.replace(java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD), "")
        .split(WordBreaks)
        .filter { it.isNotEmpty() }

private val AccentMarks = Regex("\\p{M}+")
private val WordBreaks = Regex("[^\\p{L}\\p{N}]+")

@Composable
private fun TopResultCard(
    item: YTItem,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = when (item) {
        is SongItem -> if (item.isVideoSong) stringResource(R.string.item_kind_video) else stringResource(R.string.item_kind_song)
        is AlbumItem -> stringResource(R.string.item_kind_album)
        is ArtistItem -> stringResource(R.string.item_kind_artist)
        is PlaylistItem -> stringResource(R.string.item_kind_playlist)
    }
    val detail = when (item) {
        is SongItem -> listOfNotNull(
            kind,
            item.artists.joinToString { it.name }.takeIf { it.isNotBlank() }?.let { stringResource(R.string.search_by_artist, it) },
            item.duration?.let { formatDuration(it) },
        ).joinToString("  •  ")

        is AlbumItem -> listOfNotNull(
            kind,
            item.artists?.joinToString { it.name }?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.search_by_artist, it) },
            item.year?.toString(),
        ).joinToString("  •  ")

        is ArtistItem -> kind
        is PlaylistItem -> listOfNotNull(kind, item.author?.name?.let { stringResource(R.string.search_by_artist, it) }, item.songCountText)
            .joinToString("  •  ")
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SearchHorizontalPadding)
            .clip(RoundedCornerShape(16.dp))
            .background(SearchColors.Tile)
            .searchCardBorder(16.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {

        if (item.thumbnail != null) {
            AsyncImage(
                model = item.thumbnail?.resize(256, 256),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = 0.55f
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            renderEffect = com.mochyy.mochymusic.ui.component.BlurEffectCache.get(
                                70f,
                                android.graphics.Shader.TileMode.CLAMP
                            )
                        }
                    },
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.55f to Color.Black.copy(alpha = 0.62f),
                            1f to Color.Black.copy(alpha = 0.88f),
                        )
                    )
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
        SearchArtwork(
            url = item.thumbnail,
            size = 108.dp,
            circle = item is ArtistItem,
            corner = 8.dp,
            sharedElementKey = com.mochyy.mochymusic.ui.component.coverTransitionKey(item),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = SearchColors.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = SearchColors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item !is ArtistItem) {
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(
                            if (isActive && isPlaying) R.drawable.ic_untitled_pause else R.drawable.play
                        ),
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun ResultRowChunk(
    items: List<YTItem>,
    columns: Int,
    mediaMetadataId: String?,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: (YTItem) -> Unit,
) {
    if (columns > 1) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SearchHorizontalPadding - 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                ResultRow(
                    item = item,
                    isActive = mediaMetadataId == item.id,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    onMenu = { onItemLongClick(item) },
                    horizontalPadding = 8.dp,
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(columns - items.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    } else {
        val item = items.first()
        ResultRow(
            item = item,
            isActive = mediaMetadataId == item.id,
            onClick = { onItemClick(item) },
            onLongClick = { onItemLongClick(item) },
            onMenu = { onItemLongClick(item) },
        )
    }
}

// no play state here: rows only tint the active title, so a play or pause leaves every row alone
@Composable
private fun ResultRow(
    item: YTItem,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = SearchHorizontalPadding,
) {
    val albumLabel = stringResource(R.string.item_kind_album)
    val artistLabel = stringResource(R.string.item_kind_artist)
    val playlistLabel = stringResource(R.string.item_kind_playlist)
    val subtitle = when (item) {
        is SongItem -> listOfNotNull(
            item.artists.joinToString { it.name }.takeIf { it.isNotBlank() },
            item.duration?.let { formatDuration(it) },
        ).joinToString("  •  ")

        is AlbumItem -> listOfNotNull(
            albumLabel,
            item.artists?.joinToString { it.name }?.takeIf { it.isNotBlank() },
        ).joinToString("  •  ")

        is ArtistItem -> artistLabel
        is PlaylistItem -> listOfNotNull(playlistLabel, item.author?.name).joinToString("  •  ")
    }

    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = null,
                    indication = ResultRowHighlight,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = horizontalPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchArtwork(
                url = item.thumbnail,
                size = 46.dp,
                circle = item is ArtistItem,
                sharedElementKey = com.mochyy.mochymusic.ui.component.coverTransitionKey(item),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),

                    color = if (isActive) Color(0xFF7FD1FF) else SearchColors.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = SearchColors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            SearchOverflowDots(onClick = onMenu)
        }
    }

    Column(modifier = modifier) {
        // songs slide to queue or save to the library; Settings > Gesture sets which side does which
        if (item is SongItem) {
            SongSwipeBox(
                library = { librarySwipeAction(item) },
                queue = { queueSwipeAction { item.toMediaItem() } },
            ) {
                row()
            }
        } else {
            row()
        }
        SearchRule(horizontalPadding = horizontalPadding)
    }
}

// pressing a result lights a pill from one side of the screen to the other
private val ResultRowHighlight = RoundedHighlight(cornerRadius = 100.dp, spillX = (-10).dp)

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return "$minutes:${remainder.toString().padStart(2, '0')}"
}
