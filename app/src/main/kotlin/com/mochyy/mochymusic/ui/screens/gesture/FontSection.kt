// the font picker on the customisation page: each choice shown in its own typeface

package com.mochyy.mochymusic.ui.screens.gesture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.AppFont
import com.mochyy.mochymusic.constants.AppFontKey
import com.mochyy.mochymusic.ui.theme.ClaudeSerif
import com.mochyy.mochymusic.utils.rememberEnumPreference

@Composable
internal fun FontSection(modifier: Modifier = Modifier) {
    val (font, onFont) = rememberEnumPreference(AppFontKey, defaultValue = AppFont.INTER)
    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.customise_font),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        AppFont.entries.forEachIndexed { index, option ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            val picked = option == font
            val family = if (option == AppFont.CLAUDE) ClaudeSerif else FontFamily.SansSerif
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (picked) Color.White else Color(0xFF1C1C1E))
                    .clickable { onFont(option) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = option.displayName,
                        fontFamily = family,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (picked) Color.Black else Color.White,
                    )
                    Text(
                        text = if (option == AppFont.CLAUDE) stringResource(R.string.customise_font_claude_note) else "Aa Bb Вв 123",
                        fontFamily = family,
                        fontSize = 13.sp,
                        color = (if (picked) Color.Black else Color.White).copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}
