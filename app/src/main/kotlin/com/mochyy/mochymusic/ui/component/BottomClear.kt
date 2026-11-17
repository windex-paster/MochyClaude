// the room a scrolling page leaves under its last row: just enough to clear the mini player and the nav bar

package com.mochyy.mochymusic.ui.component

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets

@Composable
fun bottomClearance(extra: Dp = 24.dp): Dp = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + extra
