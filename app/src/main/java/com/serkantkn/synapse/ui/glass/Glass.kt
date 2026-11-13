package com.serkantkn.synapse.ui.glass

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.serkantkn.synapse.ui.theme.Apple
import com.serkantkn.synapse.ui.theme.DarkMesh
import com.serkantkn.synapse.ui.theme.LightMesh

/**
 * What glass surfaces refract. Content glass reads the background; the floating bars read
 * the background plus the scrolled content, as on iOS 26.
 */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Live blur needs RenderEffect (Android 12); the lens needs runtime shaders (Android 13). */
private val supportsLiveGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The grey field behind every screen: the same 3×3 palette as the Apple apps' mesh. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val colors = if (Apple.colors.isDark) DarkMesh else LightMesh
    Canvas(modifier) {
        drawRect(colors[4])
        MeshPoints.forEachIndexed { index, (x, y) ->
            if (index == 4) return@forEachIndexed
            val center = Offset(x * size.width, y * size.height)
            val radius = size.maxDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors[index], colors[index].copy(alpha = 0f)),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}

private val MeshPoints = listOf(
    0f to 0f, 0.55f to 0f, 1f to 0f,
    0f to 0.45f, 0.6f to 0.5f, 1f to 0.4f,
    0f to 1f, 0.45f to 1f, 1f to 1f,
)

/**
 * Liquid Glass surface: blurred, refracted backdrop with a specular rim (Android 13+).
 * Android 12 gets blur without the lens; older versions a frosted fill.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    tint: Color = Color.Unspecified,
    blurRadius: Float = 12f,
): Modifier {
    val backdrop = LocalBackdrop.current
    val colors = Apple.colors
    if (backdrop == null || !supportsLiveGlass) {
        val fill = if (colors.isDark) Color(0xD91C1C1E) else Color(0xE6FFFFFF)
        return this
            .background(fill, shape)
            .then(if (tint.isSpecified) Modifier.background(tint, shape) else Modifier)
            .border(0.5.dp, Color.White.copy(alpha = if (colors.isDark) 0.14f else 0.6f), shape)
    }
    val surface = colors.glassSurface
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.dp.toPx())
            val height = minOf(16.dp.toPx(), size.minDimension / 4f)
            lens(refractionHeight = height, refractionAmount = height * 2f, depthEffect = true)
        },
        onDrawSurface = {
            drawRect(surface)
            if (tint.isSpecified) drawRect(tint)
        }
    )
}

/**
 * Screen frame: ambient background, content, and floating glass bars on top.
 * [content] gets the bars' heights as padding so it can scroll underneath them.
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val backgroundBackdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
            AmbientBackground(Modifier.fillMaxSize().layerBackdrop(backgroundBackdrop))
            CompositionLocalProvider(LocalBackdrop provides backgroundBackdrop) {
                content(
                    with(density) {
                        PaddingValues(top = topHeight.toDp(), bottom = bottomHeight.toDp())
                    }
                )
            }
        }
        CompositionLocalProvider(LocalBackdrop provides contentBackdrop) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { topHeight = it.height }
            ) { topBar() }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { bottomHeight = it.height }
            ) { bottomBar() }
        }
    }
}

/**
 * iOS 26 "soft" scroll edge: content under the bar blurs and fades out instead of
 * stopping at a hard line.
 */
@Composable
fun Modifier.scrollEdgeEffect(fromTop: Boolean = true): Modifier {
    val backdrop = LocalBackdrop.current
    val colors = Apple.colors
    val veil = if (colors.isDark) DarkMesh[4] else LightMesh[4]
    val fade = if (fromTop) listOf(Color.Black, Color.Black, Color.Transparent)
    else listOf(Color.Transparent, Color.Black, Color.Black)
    val veilBrush = if (fromTop) Brush.verticalGradient(listOf(veil.copy(alpha = 0.75f), veil.copy(alpha = 0f)))
    else Brush.verticalGradient(listOf(veil.copy(alpha = 0f), veil.copy(alpha = 0.75f)))

    if (backdrop == null || !supportsLiveGlass) {
        return this.background(veilBrush)
    }
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(fade), blendMode = BlendMode.DstIn)
        }
        .drawPlainBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = { blur(10.dp.toPx()) },
            onDrawSurface = { drawRect(veilBrush) }
        )
}
