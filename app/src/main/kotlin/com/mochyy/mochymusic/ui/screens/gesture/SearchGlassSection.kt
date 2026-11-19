// the switch on the customisation page that turns the search page's chips and field into glass

package com.mochyy.mochymusic.ui.screens.gesture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.SearchGlassKey
import com.mochyy.mochymusic.ui.component.AppSwitch
import com.mochyy.mochymusic.ui.screens.search.glassPill
import com.mochyy.mochymusic.utils.rememberPreference

@Composable
internal fun SearchGlassSection(modifier: Modifier = Modifier) {
    var glass by rememberPreference(SearchGlassKey, defaultValue = false)
    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.customise_search_glass),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                .clickable { glass = !glass }
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.customise_search_glass_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.customise_search_glass_desc), color = Color(0xFF9A9AA0), fontSize = 13.sp)
            }
            AppSwitch(checked = glass, onCheckedChange = { glass = it })
        }
        Spacer(Modifier.height(12.dp))
        // a small sample of what it looks like, over a colourful strip so the glass shows
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF3A2A6A), Color(0xFF1F5A8A), Color(0xFF8A3A5A)))),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SamplePill("YouTube Music", picked = true, glass = glass)
                SamplePill("SoundCloud", picked = false, glass = glass)
            }
        }
    }
}

@Composable
private fun SamplePill(label: String, picked: Boolean, glass: Boolean) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .drawBehind {
                if (glass) glassPill(0f, 0f, size.width, size.height, bright = picked)
                else drawRoundRect(
                    color = if (picked) Color.White else Color(0xFF121214),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                )
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (picked) Color.Black else Color.White, fontSize = 14.sp)
    }
}
