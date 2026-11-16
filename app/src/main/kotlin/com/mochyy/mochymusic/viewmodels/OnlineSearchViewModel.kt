// OnlineSearchViewModel.kt

package com.mochyy.mochymusic.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.models.filterExplicit
import com.music.innertube.models.filterVideoSongs
import com.music.innertube.models.filterYoutubeShorts
import com.music.innertube.pages.SearchSummaryPage
import com.mochyy.mochymusic.constants.HideExplicitKey
import com.mochyy.mochymusic.constants.HideVideoSongsKey
import com.mochyy.mochymusic.constants.HideYoutubeShortsKey
import com.mochyy.mochymusic.models.ItemsPage
import com.mochyy.mochymusic.module.BrowsableSourceModule
import com.mochyy.mochymusic.modules.SourceModules
import com.mochyy.mochymusic.ui.screens.search.SourceSearchState
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.get
import com.mochyy.mochymusic.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
    private val sourceModules: SourceModules,
) : ViewModel() {
    // navigation already decoded it, decoding again turned a typed + into a space
    val query = savedStateHandle.get<String>("query").orEmpty()
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    // the sources with a search of their own (SoundCloud), a chip each; YouTube Music stays the default
    val sources: List<BrowsableSourceModule> get() = sourceModules.browsable()
    val sourceStates = mutableStateMapOf<String, SourceSearchState>()

    fun source(id: String): BrowsableSourceModule? = sourceModules.browsable(id)

    /** searches [moduleId] for the page's query once; a failed one is tried again when its chip is opened again */
    fun loadSource(moduleId: String) {
        val current = sourceStates[moduleId]
        if (current is SourceSearchState.Loading || current is SourceSearchState.Done) return
        val module = source(moduleId) ?: return
        sourceStates[moduleId] = SourceSearchState.Loading
        viewModelScope.launch {
            // the module's calls block on the network, which android refuses on the main thread
            sourceStates[moduleId] = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { module.search(query) } }
                .fold(
                    onSuccess = { SourceSearchState.Done(it) },
                    onFailure = {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        reportException(it)
                        SourceSearchState.Failed(it.message ?: "no answer")
                    },
                )
        }
    }

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                if (filter == null) {
                    if (summaryPage == null) {
                        YouTube
                            .searchSummary(query)
                            .onSuccess {
                                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, true)
                                summaryPage =
                                    it.filterExplicit(
                                        hideExplicit,
                                    ).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts).withoutRepeats()
                            }.onFailure {
                                reportException(it)
                            }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        YouTube
                            .search(query, filter)
                            .onSuccess { result ->
                                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, true)
                                viewStateMap[filter.value] =
                                    ItemsPage(
                                        result.items
                                            .distinctBy { it.id }
                                            .filterExplicit(
                                                hideExplicit,
                                            )
                                            .filterVideoSongs(hideVideoSongs)
                                            .filterYoutubeShorts(hideYoutubeShorts),
                                        result.continuation,
                                    )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    // youtube sometimes lists the same song twice in one section, and two rows with one key closed the app
    private fun SearchSummaryPage.withoutRepeats() =
        copy(summaries = summaries.map { section -> section.copy(items = section.items.distinctBy { it.id }) })

    fun loadMore() {
        val filter = filter.value?.value
        viewModelScope.launch {
            if (filter == null) return@launch
            val viewState = viewStateMap[filter] ?: return@launch
            val continuation = viewState.continuation
            if (continuation != null) {
                val searchResult =
                    YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, true)
                val newItems = searchResult.items
                    .filterExplicit(hideExplicit)
                    .filterVideoSongs(hideVideoSongs)
                    .filterYoutubeShorts(hideYoutubeShorts)
                viewStateMap[filter] = ItemsPage(
                    (viewState.items + newItems).distinctBy { it.id },
                    searchResult.continuation
                )
            }
        }
    }
}
