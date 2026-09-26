package com.itantra.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * iTantra Material3 theme — always dark (space mission aesthetic).
 */
@Composable
fun iTantraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = iTantraDarkColorScheme,
        typography = iTantraTypography,
        content = content,
    )
}
