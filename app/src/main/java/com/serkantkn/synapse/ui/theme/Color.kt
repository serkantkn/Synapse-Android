package com.serkantkn.synapse.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Brand: the same cyan → blue → indigo gradient as the iOS and macOS apps.
val SynapseCyan = Color(0xFF33CCFA)
val SynapseBlue = Color(0xFF0A84FF)
val SynapseIndigo = Color(0xFF5E5CE6)
val SynapseGradient = listOf(SynapseCyan, SynapseBlue, SynapseIndigo)

/**
 * Apple's system colours, so the app reads like its iOS/macOS siblings.
 * Values follow the iOS light/dark variants.
 */
@Immutable
data class AppleColors(
    val isDark: Boolean,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val groupedRow: Color,
    val glassSurface: Color,
    val blue: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val yellow: Color,
    val teal: Color,
    val purple: Color,
    val pink: Color,
    val gray: Color,
)

val LightAppleColors = AppleColors(
    isDark = false,
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x1F787880),
    groupedRow = Color(0xEBFFFFFF),
    glassSurface = Color(0x59FFFFFF),
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30),
    yellow = Color(0xFFFFCC00),
    teal = Color(0xFF30B0C7),
    purple = Color(0xFFAF52DE),
    pink = Color(0xFFFF2D55),
    gray = Color(0xFF8E8E93),
)

val DarkAppleColors = AppleColors(
    isDark = true,
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    fill = Color(0x3D787880),
    groupedRow = Color(0xE61C1C1E),
    glassSurface = Color(0x1AFFFFFF),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    yellow = Color(0xFFFFD60A),
    teal = Color(0xFF40C8E0),
    purple = Color(0xFFBF5AF2),
    pink = Color(0xFFFF375F),
    gray = Color(0xFF8E8E93),
)

/** Grey field behind every screen (3×3, same palette as the Apple apps' MeshGradient). */
val DarkMesh = listOf(
    Color(0xFF1F1F22), Color(0xFF2C2C2F), Color(0xFF18181A),
    Color(0xFF252527), Color(0xFF0B0B0C), Color(0xFF27272A),
    Color(0xFF1A1A1C), Color(0xFF0F0F11), Color(0xFF222224),
)

val LightMesh = listOf(
    Color(0xFFE9E9EC), Color(0xFFF7F7F9), Color(0xFFE3E3E7),
    Color(0xFFF1F1F4), Color(0xFFFBFBFC), Color(0xFFE8E8EC),
    Color(0xFFE1E1E5), Color(0xFFF4F4F6), Color(0xFFECECEF),
)
