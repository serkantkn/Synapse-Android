package com.serkantkn.synapse.presentation.screens

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.serkantkn.synapse.ui.glass.GlassCard
import com.serkantkn.synapse.ui.glass.GlassScaffold
import com.serkantkn.synapse.ui.glass.GlassTopBar
import com.serkantkn.synapse.ui.glass.LargeTitle
import com.serkantkn.synapse.ui.glass.SecondaryText
import com.serkantkn.synapse.ui.glass.StatusDot
import com.serkantkn.synapse.ui.glass.SynapseMark
import com.serkantkn.synapse.ui.glass.glass
import com.serkantkn.synapse.ui.theme.Apple
import com.serkantkn.synapse.ui.theme.AppleType
import com.serkantkn.synapse.ui.theme.SynapseBlue
import com.serkantkn.synapse.ui.theme.SynapseCyan

enum class TargetPlatform {
    MAC, IOS
}

@Composable
fun DeviceSelectionScreen(
    onSelectMac: () -> Unit,
    onSelectiOS: () -> Unit,
    nearbyCihaziOSVar: Boolean = false
) {
    val scroll = rememberScrollState()
    val showTitle = scroll.value > with(LocalDensity.current) { 44.dp.toPx() }

    GlassScaffold(topBar = { GlassTopBar(title = "Synapse", showTitle = showTitle) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            LargeTitle("Synapse")

            Box(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(148.dp).glass(CircleShape, tint = SynapseBlue.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    SynapseMark(size = 76.dp)
                }
            }

            Text(
                "Nereye bağlanmak istiyorsun?",
                style = AppleType.title1,
                color = Apple.colors.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SecondaryText(
                "Telefonunu hangi cihazla eşitlemek istediğini seç.",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(28.dp))

            PlatformCard(
                icon = Icons.Rounded.Laptop,
                colors = listOf(SynapseCyan, SynapseBlue),
                title = "MacBook",
                subtitle = "Bildirimler, ekran yansıtma, pano, dosya ve daha fazlası",
                onClick = onSelectMac
            )
            Spacer(Modifier.height(14.dp))
            PlatformCard(
                icon = Icons.Rounded.PhoneIphone,
                colors = listOf(Color(0xFFBF5AF2), Color(0xFFFF375F)),
                title = "iPhone / iPad",
                subtitle = "Kişiler, fotoğraflar ve dosya paylaşımı",
                onClick = onSelectiOS,
                badge = if (nearbyCihaziOSVar) "Yakında bir iOS cihaz bulundu" else null
            )
        }
    }
}

@Composable
private fun PlatformCard(
    icon: ImageVector,
    colors: List<Color>,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: String? = null,
) {
    GlassCard(onClick = onClick, cornerRadius = 26.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(Brush.linearGradient(colors), RoundedRectangle(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = AppleType.title3, color = Apple.colors.label)
                SecondaryText(subtitle, style = AppleType.footnote)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Apple.colors.tertiaryLabel)
        }

        if (badge != null) {
            val pulse by rememberInfiniteTransition(label = "badge").animateFloat(
                initialValue = 0.8f, targetValue = 1.2f,
                animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutCubic), RepeatMode.Reverse),
                label = "pulse"
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .background(Apple.colors.green.copy(alpha = 0.14f), Capsule())
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.scale(pulse)) { StatusDot(Apple.colors.green, 7.dp) }
                Spacer(Modifier.width(8.dp))
                Text(badge, style = AppleType.footnote, color = Apple.colors.green)
            }
        }
    }
}
