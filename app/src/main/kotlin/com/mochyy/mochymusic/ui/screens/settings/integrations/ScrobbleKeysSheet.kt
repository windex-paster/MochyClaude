// the api key and secret of a last.fm / libre.fm account, for a build that has none of its own (a fork, say)

package com.mochyy.mochymusic.ui.screens.settings.integrations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.scrobble.ScrobbleSite
import com.mochyy.mochymusic.ui.component.SheetLabel
import com.mochyy.mochymusic.ui.component.SheetPillButton
import com.mochyy.mochymusic.ui.component.SheetTextField
import com.mochyy.mochymusic.ui.screens.search.SearchColors
import com.mochyy.mochymusic.utils.rememberPreference

@Composable
fun ScrobbleKeysContent(site: ScrobbleSite, onDone: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val (savedKey, setKey) = rememberPreference(site.ownKeyKey, defaultValue = "")
    val (savedSecret, setSecret) = rememberPreference(site.ownSecretKey, defaultValue = "")
    var key by remember { mutableStateOf(savedKey) }
    var secret by remember { mutableStateOf(savedSecret) }

    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            stringResource(R.string.scrobble_keys_title, site.label),
            color = SearchColors.Primary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.scrobble_keys_body, site.label),
            color = SearchColors.Secondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))

        SheetLabel(stringResource(R.string.scrobble_keys_key))
        Spacer(Modifier.height(8.dp))
        SheetTextField(
            value = key,
            onValueChange = { key = it },
            placeholder = stringResource(R.string.scrobble_keys_key_hint),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(18.dp))

        SheetLabel(stringResource(R.string.scrobble_keys_secret))
        Spacer(Modifier.height(8.dp))
        SheetTextField(
            value = secret,
            onValueChange = { secret = it },
            placeholder = stringResource(R.string.scrobble_keys_secret_hint),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(22.dp))

        SheetPillButton(
            text = stringResource(R.string.scrobble_keys_save),
            onClick = {
                setKey(key.trim())
                setSecret(secret.trim())
                onDone()
            },
            enabled = key.isNotBlank() && secret.isNotBlank(),
        )
        if (site == ScrobbleSite.LASTFM) {
            Text(
                stringResource(R.string.scrobble_keys_get),
                color = SearchColors.Secondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clickable { uriHandler.openUri("https://www.last.fm/api/account/create") }
                    .padding(vertical = 10.dp),
            )
        }
    }
}
