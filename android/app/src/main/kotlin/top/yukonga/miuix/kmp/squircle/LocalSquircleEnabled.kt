// Compatible with compose-miuix-ui 0.9.4. Copyright 2026 compose-miuix-ui contributors.
// SPDX-License-Identifier: Apache-2.0
package top.yukonga.miuix.kmp.squircle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
val LocalSquircleEnabled = staticCompositionLocalOf { true }
@Composable @ReadOnlyComposable fun isSquircleEnabled(): Boolean = LocalSquircleEnabled.current
