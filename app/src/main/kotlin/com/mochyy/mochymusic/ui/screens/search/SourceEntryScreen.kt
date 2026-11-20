// the page of one artist, album or playlist from a source's own search: its songs, played straight from that source

package com.mochyy.mochymusic.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.extensions.togglePlayPause
import com.mochyy.mochymusic.modules.BrowsedIds
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.viewmodels.SourceEntryViewModel

@Composable
fun SourceEntryScreen(
    navController: NavController,
    viewModel: SourceEntryViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val module = viewModel.module
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    BackHandler { navController.navigateUp() }

    Box(modifier = Modifier.fillMaxSize().background(SearchColors.page(false))) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 96.dp, bottom = bottomInset + 24.dp),
        ) {
            sourceResultItems(
                state = viewModel.state,
                activeMediaId = mediaMetadata?.id,
                mediaIdOf = { hit -> BrowsedIds.mediaId(viewModel.moduleId, hit.id) },
                onClick = { index, hit ->
                    val done = viewModel.state as? SourceSearchState.Done
                    if (module != null && done != null) {
                        if (BrowsedIds.mediaId(viewModel.moduleId, hit.id) == mediaMetadata?.id) {
                            playerConnection.player.togglePlayPause()
                        } else {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = viewModel.title.ifBlank { module.searchLabel },
                                    items = done.hits.map { BrowsedIds.metadataOf(viewModel.moduleId, it).toMediaItem() },
                                    startIndex = index,
                                ),
                            )
                        }
                    }
                },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SearchColors.page(false))
                .padding(top = 48.dp, bottom = 12.dp, start = SearchHorizontalPadding, end = SearchHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = null,
                tint = SearchColors.Secondary,
                modifier = Modifier.size(24.dp).clickable { navController.navigateUp() },
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = viewModel.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = SearchColors.Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
