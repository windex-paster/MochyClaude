// the page of songs marked "don't recommend": they stay out of autoplay, radios and home; search them, filter by when, take one back

package com.mochyy.mochymusic.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.screens.search.SearchArtwork
import com.mochyy.mochymusic.ui.screens.search.SearchColors
import com.mochyy.mochymusic.utils.NotRecommended

private enum class DislikeSpan(val days: Int, val label: Int) {
    All(0, R.string.dislikes_all_time),
    Today(1, R.string.dislikes_today),
    Week(7, R.string.dislikes_7d),
    Month(30, R.string.dislikes_30d),
}

@Composable
fun DislikesScreen(navController: NavController) {
    val version by NotRecommended.version.collectAsState()
    var query by remember { mutableStateOf("") }
    var span by remember { mutableStateOf(DislikeSpan.All) }
    val all = remember(version) { NotRecommended.entries() }
    val shown = remember(all, query, span) {
        val from = if (span.days == 0) 0L else System.currentTimeMillis() - span.days * 86_400_000L
        all.filter { e ->
            // songs added before dates were kept have no date, so only "all time" shows them
            (span.days == 0 || e.at >= from) &&
                (query.isBlank() || (e.title ?: "").contains(query, true) || (e.artist ?: "").contains(query, true))
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.statusBars)) {
        Box(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp).size(44.dp).clip(RoundedCornerShape(50)).background(SearchColors.TileHigh)
                .clickable { navController.navigateUp() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.back_button_desc), tint = Color.White)
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.dislikes_title), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.dislikes_hint), color = Color(0xFF8E8E93), fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF151517)).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.search), null, tint = Color(0xFF8E8E93), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(stringResource(R.string.dislikes_search), color = Color(0xFF8E8E93), fontSize = 15.sp)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DislikeSpan.entries) { s ->
                        val on = s == span
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color(0xFF212124))
                                .clickable { span = s }.padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(stringResource(s.label), color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.dislikes_count, shown.size), color = Color(0xFF8E8E93), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (shown.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.dislikes_empty), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            items(shown, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchArtwork(url = e.thumbnail, size = 52.dp, corner = 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title ?: e.id, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.artist != null) Text(e.artist, color = Color(0xFF8E8E93), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF212124)).clickable { NotRecommended.remove(e.id) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(stringResource(R.string.dislikes_remove), color = Color.White, fontSize = 12.sp) }
                }
            }
        }
    }
}
