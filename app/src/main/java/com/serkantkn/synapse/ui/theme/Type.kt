package com.serkantkn.synapse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Apple's text styles (iOS Dynamic Type, "Large" size), so text sizes match the iPhone app. */
@Immutable
object AppleType {
    private val family = FontFamily.Default

    val largeTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.2.sp)
    val title1 = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp)
    val title2 = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)
    val title3 = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp)
    val headline = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp)
    val callout = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 21.sp)
    val subheadline = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 13.sp)
}

/** Material slots mapped onto the Apple styles, for any Material component still in use. */
val Typography = Typography(
    displaySmall = AppleType.largeTitle,
    headlineLarge = AppleType.largeTitle,
    headlineMedium = AppleType.title1,
    headlineSmall = AppleType.title2,
    titleLarge = AppleType.title3,
    titleMedium = AppleType.headline,
    titleSmall = AppleType.subheadline.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = AppleType.body,
    bodyMedium = AppleType.subheadline,
    bodySmall = AppleType.footnote,
    labelLarge = AppleType.subheadline.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = AppleType.caption.copy(fontWeight = FontWeight.Medium),
    labelSmall = AppleType.caption2,
)
