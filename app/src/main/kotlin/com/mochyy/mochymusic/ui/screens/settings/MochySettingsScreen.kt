// MochySettingsScreen.kt

package com.mochyy.mochymusic.ui.screens.settings

import com.mochyy.mochymusic.modules.browser.ModuleFeed
import com.mochyy.mochymusic.ui.screens.settings.modules.ModuleBrowserRoute
import com.mochyy.mochymusic.ui.component.navigateToTab
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import com.mochyy.mochymusic.ui.screens.update.showUpdateSheet
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.modules.skin.ActiveSkin
import com.mochyy.mochymusic.ui.screens.devices.showDeviceIntegration
import com.mochyy.mochymusic.ui.screens.settings.integrations.IntegrationsRoute
import com.mochyy.mochymusic.constants.ProfilePicUriKey
import com.mochyy.mochymusic.ui.component.GlassState
import com.mochyy.mochymusic.ui.component.SettingsHighlight
import com.mochyy.mochymusic.ui.component.glassRoot
import com.mochyy.mochymusic.ui.screens.search.SearchAvatar
import com.mochyy.mochymusic.ui.screens.search.SearchField
import com.mochyy.mochymusic.ui.screens.search.SearchGlassTopBar
import com.mochyy.mochymusic.ui.screens.search.rememberScrollCollapseProgress
import com.mochyy.mochymusic.ui.screens.search.searchTopBarHeight
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.rememberPreference

internal val BubbleColor = Color(0xFF1C1C1E)
private val SelectedBubbleColor = Color(0xFF3A3A3E)

// the header holds the mochy mark with the big "Settings" under it, taller than search's title-only header
private val SettingsTitleBlockHeight = 88.dp
private val SettingsMarkSize = 32.dp
internal val IconBgColor = Color(0xFF333338)

@Composable
fun MochySettingsScreen(navController: NavController, pane: SettingsPane? = null) {
    val focusManager = LocalFocusManager.current
    val sheets = com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState.current
    val devices = com.mochyy.mochymusic.ui.screens.devices.rememberDevicesLink()
    // on a wide screen the list stays put and its pages open beside it; device integration is a sheet over either
    val open: (String) -> Unit = { route ->
        when {
            route == com.mochyy.mochymusic.ui.screens.devices.DeviceIntegrationRoute -> sheets.showDeviceIntegration(devices)
            pane != null -> navController.openSettingsPage(route)
            else -> navController.navigate(route)
        }
    }
    val selected = pane?.selectedRoute
    val scrollState = rememberScrollState()
    val glassState = remember { GlassState() }

    val (profilePicUri) = rememberPreference(ProfilePicUriKey, "")

    var query by remember { mutableStateOf(TextFieldValue()) }
    var isSearchFocused by remember { mutableStateOf(false) }

    val isSearching = isSearchFocused || query.text.isNotBlank()

    BackHandler(enabled = isSearching) {
        query = TextFieldValue()
        isSearchFocused = false
        focusManager.clearFocus()
    }

    val context = LocalContext.current
    // picked again each time the list opens, so a tip that was just followed stops showing
    val recommendations = remember(context) { pickRecommendations(context) }
    val results = remember(query.text, context) { searchSettings(context, query.text) }

    // Same latch-and-settle collapse as Library/Search: crossing the threshold always finishes
    // the morph, even after the user stops scrolling mid-gesture.
    val collapseProgress by rememberScrollCollapseProgress(scrollState)

    // Animated fade for content when search bar is tapped
    val contentAlpha by animateFloatAsState(
        targetValue = if (isSearching) 0f else 1f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "contentAlpha"
    )
    // flips only at the ends of the fade, so the page isn't recomposed on every frame of it
    val showContent by remember { derivedStateOf { contentAlpha > 0.001f } }

    val customBg = ActiveSkin.appBackground?.let { bg ->
        bg.background.screens.isEmpty() || "mochy_settings" in bg.background.screens || "settings" in bg.background.screens
    } == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (customBg) Color.Transparent else Color.Black),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassRoot(glassState, isActive = { !scrollState.isScrollInProgress && collapseProgress > 0f }),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(searchTopBarHeight(titleBlockHeight = SettingsTitleBlockHeight)))

            // Search results view when user types
            AnimatedVisibility(
                visible = query.text.isNotBlank(),
                enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(160, easing = FastOutSlowInEasing)) { 20 },
                exit = fadeOut(tween(120, easing = FastOutSlowInEasing))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (results.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_nothing_matches, query.text),
                            fontFamily = InterFontFamily,
                            fontSize = 15.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                        )
                    } else {
                        results.forEach { entry ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(BubbleColor)
                                    .clickable {
                                        SettingsHighlight.pending = entry.highlightKey
                                        open(entry.route)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(IconBgColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.settings),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(11.dp))
                                    Column {
                                        Text(
                                            text = (entry.titleRes ?: LocalizedSettingsTitles[entry.title])?.let { stringResource(it) } ?: entry.title,
                                            fontFamily = InterFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 16.sp,
                                            color = Color.White
                                        )
                                        Text(
                                            text = (entry.pageRes ?: LocalizedSettingsPages[entry.page])?.let { stringResource(it) } ?: entry.page,
                                            fontFamily = InterFontFamily,
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.55f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Normal Settings Content (fades smoothly to black when search is focused)
            if (showContent) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = contentAlpha }
                ) {
                    RecommendedCard(recommendations = recommendations, open = open)

                    // a build without the module feed (most forks) has no store to open
                    if (ModuleFeed.FeedUrl.isNotBlank()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        ModuleStoreCard(
                            onClick = { navController.navigate(ModuleBrowserRoute) },
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .height(0.8.dp)
                            .background(Color.White.copy(alpha = 0.1f))
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    SettingsSections(navController, open, selected)
                }
            }

            val bottomInset = com.mochyy.mochymusic.LocalPlayerAwareWindowInsets.current.asPaddingValues()
            val bottomPadding = bottomInset.calculateBottomPadding()
            val bottomSpacerHeight = bottomPadding + 24.dp

            // Headroom spacer for smooth scrolling past collapse threshold and bottom bar
            Spacer(modifier = Modifier.height(bottomSpacerHeight))
        }
        }

        // Top bar: Mochy mark + "Settings" title slide away on scroll (progressive glass blur
        // behind the gradient, matching Library/Search) while the search field glides up to sit
        // beside the profile avatar.
        SearchGlassTopBar(
            glassState = glassState,
            progressProvider = { collapseProgress },
            pureBlack = true,
            title = stringResource(R.string.settings),
            titleBlockHeight = SettingsTitleBlockHeight,
            titleContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mochy_mark),
                        contentDescription = "Mochy",
                        tint = Color.White,
                        modifier = Modifier.size(SettingsMarkSize),
                    )
                    Text(
                        text = stringResource(R.string.settings),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        lineHeight = 40.sp,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            },
            trailing = {
                // at rest it sits beside the mark like the design, and drops to the search field's middle as the header folds
                SearchAvatar(
                    modifier = Modifier.graphicsLayer {
                        val lift = (SettingsTitleBlockHeight / 2 - SettingsMarkSize / 2).toPx()
                        translationY = -(1f - collapseProgress) * lift
                    },
                    imageUrl = profilePicUri.ifBlank { null },
                    onClick = {
                        // back to the profile page these were opened from, not a second copy of it on top
                        if (!navController.popBackStack("settings", inclusive = false)) navController.navigateToTab("settings")
                    },
                )
            },
        ) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                onSearch = {},
                placeholder = stringResource(R.string.settings_search_placeholder),
                focused = isSearchFocused,
                onFocusChanged = { isSearchFocused = it },
                trailing = if (query.text.isNotEmpty()) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.settings_clear_search),
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable { query = TextFieldValue() },
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
internal fun CategoryBubble(
    title: String,
    iconRes: Int,
    onClick: () -> Unit,
    // lit while its page is open beside the list on a wide screen
    selected: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) SelectedBubbleColor else BubbleColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(IconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = Color.White,
            )
        }
    }
}


/** a card holding a group of [SettingsRow]s, as one rounded block */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(BubbleColor),
        content = content,
    )
}

/**
 * One row of a [SettingsGroup]: a rounded-square [tile] colour with its white icon, the title, a
 * chevron at the end, and a hairline under it that starts at the title unless it is the [last] row.
 */
@Composable
internal fun SettingsRow(
    title: String,
    iconRes: Int,
    tile: Color,
    onClick: () -> Unit,
    // lit while its page is open beside the list on a wide screen
    selected: Boolean = false,
    last: Boolean = false,
) {
    Column(modifier = Modifier.background(if (selected) SelectedBubbleColor else Color.Transparent)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tile),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = title,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.chevron_right_px),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
        if (!last) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 64.dp)
                    .height(0.5.dp)
                    .background(Color.White.copy(alpha = 0.1f))
            )
        }
    }
}


/** the small grey label over a card: "Playback and audio", "About the app" */
@Composable
internal fun SettingsLabel(text: String) {
    Text(
        text = text,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        color = Color(0xFF8E8E93),
        modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp),
    )
}

/** a row with a dark icon tile, the title, a grey line under it and a chevron; [last] drops the hairline */
@Composable
internal fun SettingsLinkRow(
    title: String,
    subtitle: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    last: Boolean = false,
) {
    Column(modifier = Modifier.background(if (selected) SelectedBubbleColor else Color.Transparent)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF2C2C2F)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = painterResource(iconRes), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White, maxLines = 1)
                Text(subtitle, fontFamily = InterFontFamily, fontSize = 13.sp, color = Color(0xFF8E8E93), maxLines = 2)
            }
            Icon(
                painter = painterResource(R.drawable.chevron_right_px),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
        if (!last) {
            Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.1f)))
        }
    }
}

/** a plain row with a title on the left and its value on the right, nothing to open */
@Composable
internal fun SettingsValueRow(title: String, value: String, last: Boolean = false) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(title, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
            Text(value, fontFamily = InterFontFamily, fontSize = 15.sp, color = Color(0xFF8E8E93), maxLines = 1)
        }
        if (!last) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.1f)))
    }
}

/** the settings sections (appearance, audio, services, about), used by the settings list and the profile page */
@Composable
internal fun SettingsSections(
    navController: NavController,
    open: (String) -> Unit,
    selected: String? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val sheets = com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState.current
                    // laid out as in the design: small grey section labels over dark cards, each row a tile, a title and a line under it
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
    ) {
        SettingsLabel(stringResource(R.string.mset_section_look))
        SettingsGroup {
            SettingsLinkRow(stringResource(R.string.settings_customisation_category), stringResource(R.string.mset_customisation_sub), R.drawable.palette, selected == "customisation_settings", { open("customisation_settings") })
            SettingsLinkRow(stringResource(R.string.settings_now_playing), stringResource(R.string.mset_now_playing_sub), R.drawable.lyrics, selected == NowPlayingSettingsRoute, { open(NowPlayingSettingsRoute) })
            SettingsLinkRow(stringResource(R.string.settings_app_behaviour), stringResource(R.string.mset_behaviour_sub), R.drawable.language, selected == "general_settings", { open("general_settings") }, last = true)
        }

        SettingsLabel(stringResource(R.string.mset_section_audio))
        SettingsGroup {
            SettingsLinkRow(stringResource(R.string.audio_quality), stringResource(R.string.mset_quality_sub), R.drawable.music_note, selected == "audio_quality", { open("audio_quality") })
            SettingsLinkRow(stringResource(R.string.mset_equalizer), stringResource(R.string.mset_equalizer_sub), R.drawable.equalizer, selected == "equalizer", { open("equalizer") })
            SettingsLinkRow(stringResource(R.string.settings_playback_category), stringResource(R.string.mset_playback_sub), R.drawable.speed, selected == PlaybackSettingsRoute, { open(PlaybackSettingsRoute) })
            SettingsLinkRow(stringResource(R.string.mset_crossfade), stringResource(R.string.mset_crossfade_sub), R.drawable.timer, selected == "crossmix_settings", { open("crossmix_settings") })
            SettingsLinkRow(stringResource(R.string.settings_audio_lab_category), stringResource(R.string.mset_audio_lab_sub), R.drawable.tune, selected == "audio_lab", { open("audio_lab") }, last = true)
        }

        SettingsLabel(stringResource(R.string.mset_section_diagnostics))
        SettingsGroup {
            SettingsLinkRow(stringResource(R.string.mset_diagnostics), stringResource(R.string.mset_diagnostics_sub), R.drawable.bug_report, selected == "playback_diagnostics", { open("playback_diagnostics") }, last = true)
        }

        SettingsLabel(stringResource(R.string.mset_section_storage))
        SettingsGroup {
            SettingsLinkRow(stringResource(R.string.storage), stringResource(R.string.mset_storage_sub), R.drawable.storage, selected == "storage_settings", { open("storage_settings") }, last = true)
        }

        SettingsLabel(stringResource(R.string.mset_section_services))
        SettingsGroup {
            SettingsLinkRow(stringResource(R.string.integrations), stringResource(R.string.mset_integrations_sub), R.drawable.integration, selected == IntegrationsRoute, { open(IntegrationsRoute) })
            SettingsLinkRow(stringResource(R.string.devices_title), stringResource(R.string.mset_devices_sub), R.drawable.cast_connected, false, { open(com.mochyy.mochymusic.ui.screens.devices.DeviceIntegrationRoute) })
            SettingsLinkRow(stringResource(R.string.settings_modules_category), stringResource(R.string.mset_modules_sub), R.drawable.library_add, selected == "modules", { open("modules") }, last = true)
        }

        // pages the modules that are on bring to settings, right under modules
        Spacer(Modifier.height(12.dp))
        com.mochyy.mochymusic.ui.screens.settings.modules.ModuleSettingsPages(open = open, selected = selected)

        SettingsLabel(stringResource(R.string.mset_section_about))
        // the version sheet checks for a newer release as soon as it opens
        SettingsGroup {
            SettingsLinkRow(
                title = stringResource(R.string.settings_check_update),
                subtitle = stringResource(R.string.mset_update_sub),
                iconRes = R.drawable.update,
                selected = false,
                onClick = {
                    sheets.showUpdateSheet(
                        com.mochyy.mochymusic.core.updater.UpdateState.Checking,
                        onOpenDevSpace = { person -> navController.navigate("developer_space?person=$person") },
                    )
                },
            )
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            SettingsLinkRow(
                title = stringResource(R.string.mset_telegram),
                subtitle = stringResource(R.string.mset_telegram_sub),
                iconRes = R.drawable.link,
                selected = false,
                onClick = { runCatching { uriHandler.openUri("http://t.me/genedevs") } },
            )
            SettingsValueRow(stringResource(R.string.mset_version), com.mochyy.mochymusic.BuildConfig.VERSION_NAME, last = true)
        }
    }
}
