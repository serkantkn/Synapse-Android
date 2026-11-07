package com.serkantkn.synapse.presentation.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colors
private val SurfaceDark = Color(0xFF0F1318)
private val CardDark = Color(0xFF1A1F27)
private val CardBorder = Color(0xFF2A3040)
private val Cyan400 = Color(0xFF26C6DA)
private val Blue500 = Color(0xFF2196F3)
private val Green400 = Color(0xFF66BB6A)

enum class TargetPlatform {
    MAC, IOS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionScreen(
    onSelectMac: () -> Unit,
    onSelectiOS: () -> Unit,
    nearbyCihaziOSVar: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "pulse"
    )

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(listOf(Cyan400, Blue500))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Hub, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Text("Synapse", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            // Title
            Text(
                "Nereye Bağlanmak\nİstiyorsun?",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
                lineHeight = 36.sp
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Android cihazınızı hangi platform ile senkronize etmek istediğinizi seçin",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(40.dp))

            // MacBook option
            DeviceOptionCard(
                icon = Icons.Filled.Laptop,
                title = "MacBook",
                subtitle = "Tam senkronizasyon — Bildirimler, Ekran Yansıtma, Panoya Paylaşım ve daha fazlası",
                gradientColors = listOf(Cyan400, Blue500),
                onClick = onSelectMac
            )

            Spacer(Modifier.height(16.dp))

            // iOS option
            DeviceOptionCard(
                icon = Icons.Filled.PhoneIphone,
                title = "iPhone / iPad",
                subtitle = "Kişiler, Fotoğraflar ve Dosya paylaşımı",
                gradientColors = listOf(Color(0xFF7C4DFF), Color(0xFFE040FB)),
                onClick = onSelectiOS,
                badge = if (nearbyCihaziOSVar) "Yakında bir iOS cihaz algılandı" else null,
                badgePulseScale = pulseScale
            )

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DeviceOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    badge: String? = null,
    badgePulseScale: Float = 1f
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CardDark,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(gradientColors.map { it.copy(alpha = 0.2f) })),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = gradientColors.first(), modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        lineHeight = 16.sp
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight, null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Nearby badge
            if (badge != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Green400.copy(alpha = 0.1f))
                        .border(0.5.dp, Green400.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .scale(badgePulseScale)
                            .clip(CircleShape)
                            .background(Green400)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(badge, fontSize = 12.sp, color = Green400, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
