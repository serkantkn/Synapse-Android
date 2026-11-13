package com.serkantkn.synapse.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle
import com.serkantkn.synapse.domain.model.ConnectionStatus
import com.serkantkn.synapse.ui.theme.Apple
import com.serkantkn.synapse.ui.theme.AppleType
import com.serkantkn.synapse.ui.theme.SynapseBlue
import com.serkantkn.synapse.ui.theme.SynapseGradient
import kotlin.math.cos
import kotlin.math.sin

// MARK: - Press feedback

/** iOS 26 interactive glass: the control swells slightly while pressed. */
@Composable
private fun Modifier.pressScale(interaction: MutableInteractionSource, enabled: Boolean = true): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

// MARK: - Cards

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    tint: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = remember(cornerRadius) { RoundedRectangle(cornerRadius) }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressScale(interaction) else Modifier)
            .glass(shape, tint)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                } else Modifier
            )
            .padding(contentPadding),
        content = content
    )
}

// MARK: - Buttons

/**
 * Capsule button. Prominent = tinted glass with white label (iOS `.glassProminent`);
 * otherwise clear glass with a tinted label (iOS `.glass`).
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    prominent: Boolean = false,
    tint: Color = Apple.colors.blue,
    enabled: Boolean = true,
    large: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = remember { Capsule() }
    val contentColor = when {
        !enabled -> Apple.colors.tertiaryLabel
        prominent -> Color.White
        else -> tint
    }
    Row(
        modifier = modifier
            .heightIn(min = if (large) 52.dp else 40.dp)
            .pressScale(interaction, enabled)
            .glass(shape, tint = if (prominent && enabled) tint.copy(alpha = 0.88f) else Color.Unspecified)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (large) 24.dp else 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(if (large) 20.dp else 17.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = if (large) AppleType.headline else AppleType.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

/** Round glass button for bar actions (back, refresh…). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Apple.colors.label,
    size: Dp = 44.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction)
            .glass(CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

// MARK: - Bars

/**
 * Floating top bar: back button, inline title (once the large title has scrolled away)
 * and trailing actions over a soft scroll-edge blur.
 */
@Composable
fun GlassTopBar(
    title: String = "",
    showTitle: Boolean = true,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().scrollEdgeEffect())
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(60.dp)
                .padding(horizontal = 16.dp)
        ) {
            if (onBack != null) {
                GlassIconButton(
                    Icons.AutoMirrored.Rounded.ArrowBackIos, "Geri", onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
            AnimatedVisibility(
                visible = showTitle && title.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 112.dp)
            ) {
                Text(title, style = AppleType.headline, color = Apple.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

/** Large title at the top of a scrolling screen, as in iOS navigation. */
@Composable
fun LargeTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp)) {
        Text(title, style = AppleType.largeTitle, color = Apple.colors.label)
        if (subtitle != null) {
            Text(subtitle, style = AppleType.subheadline, color = Apple.colors.secondaryLabel)
        }
    }
}

// MARK: - Brand

/** The Synapse hub: a centre node linked to three others (same glyph as the Apple apps). */
@Composable
fun HubGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val side = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        val reach = side * 0.36f
        val node = side * 0.12f
        val points = listOf(-90.0, 30.0, 150.0).map { degrees ->
            val radians = Math.toRadians(degrees)
            Offset(center.x + reach * cos(radians).toFloat(), center.y + reach * sin(radians).toFloat())
        }
        points.forEach { drawLine(color.copy(alpha = 0.85f), center, it, strokeWidth = node * 0.7f, cap = StrokeCap.Round) }
        (points + center).forEach { drawCircle(color, radius = node, center = it) }
    }
}

@Composable
fun SynapseMark(size: Dp = 32.dp) {
    Box(
        Modifier
            .size(size)
            .shadow(size * 0.2f, RoundedRectangle(size * 0.28f), ambientColor = SynapseBlue, spotColor = SynapseBlue)
            .background(Brush.linearGradient(SynapseGradient), RoundedRectangle(size * 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        HubGlyph(Modifier.size(size * 0.64f))
    }
}

// MARK: - Small pieces

val ConnectionStatus.title: String
    get() = when (this) {
        ConnectionStatus.CONNECTED -> "Bağlı"
        ConnectionStatus.DISCOVERING -> "Arıyor"
        ConnectionStatus.PAIRING -> "Eşleşiyor"
        ConnectionStatus.ERROR -> "Hata"
        ConnectionStatus.DISCONNECTED -> "Çevrimdışı"
    }

@Composable
fun ConnectionStatus.tint(): Color = when (this) {
    ConnectionStatus.CONNECTED -> Apple.colors.green
    ConnectionStatus.DISCOVERING -> Apple.colors.orange
    ConnectionStatus.PAIRING -> Apple.colors.blue
    ConnectionStatus.ERROR -> Apple.colors.red
    ConnectionStatus.DISCONNECTED -> Apple.colors.gray
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .shadow(size / 2, CircleShape, ambientColor = color, spotColor = color)
            .background(color, CircleShape)
    )
}

/** Status in a glass capsule, for the top bar. */
@Composable
fun StatusBadge(status: ConnectionStatus) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .glass(Capsule())
            .padding(horizontal = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Durum: ${status.title}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(status.tint())
        Spacer(Modifier.width(7.dp))
        Text(status.title, style = AppleType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Apple.colors.label)
    }
}

/** Icon in a tinted rounded tile, as in iOS Settings. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .background(color.copy(alpha = 0.16f), RoundedRectangle(size * 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

/** Numbered circle with the brand gradient, for "how to" steps. */
@Composable
fun StepRow(number: Int, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(28.dp)
                .background(Brush.linearGradient(SynapseGradient), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", style = AppleType.subheadline.copy(fontWeight = FontWeight.Bold), color = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = AppleType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Apple.colors.label)
            Text(detail, style = AppleType.footnote, color = Apple.colors.secondaryLabel)
        }
    }
}

// MARK: - iOS switch

@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = Apple.colors
    val track by animateColorAsState(if (checked) colors.green else colors.fill, label = "track")
    val offset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "thumb"
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(Capsule())
            .background(track)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}

// MARK: - Segmented control

/** iOS segmented control on glass; the selected segment is a lifted capsule. */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Apple.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .glass(Capsule())
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val background by animateColorAsState(
                if (selected) (if (colors.isDark) Color.White.copy(alpha = 0.18f) else Color.White) else Color.Transparent,
                label = "segment"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(Capsule())
                    .background(background)
                    .selectable(selected = selected, role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = AppleType.subheadline.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                    color = colors.label
                )
            }
        }
    }
}

// MARK: - Grouped list (iOS inset grouped)

/** The UI is Turkish: "i" must become "İ", not "I", when upper-cased. */
private val TurkishLocale = java.util.Locale.forLanguageTag("tr")

/** Section header above a grouped list, in iOS small caps style. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(TurkishLocale),
        style = AppleType.footnote,
        color = Apple.colors.secondaryLabel,
        modifier = modifier.padding(start = 20.dp, bottom = 6.dp, top = 8.dp)
    )
}

/** Position of a row inside its grouped section; decides which corners are rounded. */
enum class RowPosition { Single, First, Middle, Last }

fun rowPosition(index: Int, count: Int): RowPosition = when {
    count == 1 -> RowPosition.Single
    index == 0 -> RowPosition.First
    index == count - 1 -> RowPosition.Last
    else -> RowPosition.Middle
}

private fun rowShape(position: RowPosition): Shape {
    val r = 26.dp
    return when (position) {
        RowPosition.Single -> RoundedRectangle(r)
        RowPosition.First -> UnevenRoundedRectangle(topStart = r, topEnd = r)
        RowPosition.Middle -> RectangleShape
        RowPosition.Last -> UnevenRoundedRectangle(bottomEnd = r, bottomStart = r)
    }
}

/**
 * One row of an inset grouped list. Rows are opaque like iOS table cells (only the bars
 * and cards are glass), so long lists stay cheap to draw.
 */
@Composable
fun GroupedRow(
    position: RowPosition,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Apple.colors
    val shape = rowShape(position)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.groupedRow)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f), content = content)
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.tertiaryLabel)
            }
        }
        if (position == RowPosition.First || position == RowPosition.Middle) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (leading != null) 70.dp else 16.dp)
                    .height(0.5.dp)
                    .background(colors.separator)
            )
        }
    }
}

// MARK: - Empty / info text

@Composable
fun CenteredMessage(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(84.dp).glass(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            GradientIcon(icon, Modifier.size(36.dp))
        }
        Text(title, style = AppleType.title3, color = Apple.colors.label, textAlign = TextAlign.Center)
        Text(message, style = AppleType.subheadline, color = Apple.colors.secondaryLabel, textAlign = TextAlign.Center)
        extra()
    }
}

/** Icon filled with the brand gradient, like SF Symbols with `.foregroundStyle(gradient)`. */
@Composable
fun GradientIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Icon(
        icon,
        contentDescription = null,
        tint = Color.White,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.linearGradient(SynapseGradient, start = Offset.Zero, end = Offset(size.width, size.height)),
                    blendMode = BlendMode.SrcIn
                )
            }
    )
}

/** Box with content centred, used as a tappable full-width area. */
@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center, content = content)
}

@Composable
fun SecondaryText(text: String, style: TextStyle = AppleType.subheadline, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(text, style = style, color = Apple.colors.secondaryLabel, modifier = modifier, textAlign = textAlign)
}
