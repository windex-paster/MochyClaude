// SourceEntryViewModel.kt

package com.mochyy.mochymusic.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.module.BrowsableSourceModule
import com.mochyy.mochymusic.module.EntryKind
import com.mochyy.mochymusic.modules.SourceModules
import com.mochyy.mochymusic.ui.screens.search.SourceSearchState
import com.mochyy.mochymusic.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** the songs of one artist, album or playlist of a source's own search */
@HiltViewModel
class SourceEntryViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val sourceModules: SourceModules,
) : ViewModel() {
    val moduleId = savedStateHandle.get<String>("moduleId").orEmpty()
    val kind = runCatching { EntryKind.valueOf(savedStateHandle.get<String>("kind").orEmpty()) }.getOrDefault(EntryKind.Playlist)
    val entryId = savedStateHandle.get<String>("entryId").orEmpty()
    val title = savedStateHandle.get<String>("title").orEmpty().takeIf { it != "-" }.orEmpty()

    val module: BrowsableSourceModule? get() = sourceModules.browsable(moduleId)

    var state by mutableStateOf<SourceSearchState>(SourceSearchState.Loading)
        private set

    init {
        load()
    }

    fun load() {
        val module = module ?: run {
            state = SourceSearchState.Failed("this source is off")
            return
        }
        state = SourceSearchState.Loading
        viewModelScope.launch {
            state = runCatching { withContext(Dispatchers.IO) { module.entryTracks(kind, entryId) } }.fold(
                onSuccess = { SourceSearchState.Done(it) },
                onFailure = {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    reportException(it)
                    SourceSearchState.Failed(it.message ?: "no answer")
                },
            )
        }
    }
}
