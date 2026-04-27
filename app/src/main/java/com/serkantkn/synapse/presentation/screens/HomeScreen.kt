package com.serkantkn.synapse.presentation.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.serkantkn.synapse.domain.model.ConnectionStatus
import com.serkantkn.synapse.domain.model.Device
import com.serkantkn.synapse.presentation.viewmodel.SynapseViewModel

// Premium color palette
private val Cyan400 = Color(0xFF26C6DA)
private val Blue500 = Color(0xFF2196F3)
private val Green400 = Color(0xFF66BB6A)
private val Red400 = Color(0xFFEF5350)
private val Amber400 = Color(0xFFFFCA28)
private val SurfaceDark = Color(0xFF0F1318)
private val CardDark = Color(0xFF1A1F27)
private val CardBorder = Color(0xFF2A3040)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: SynapseViewModel,
    onNavigateToPairing: () -> Unit,
    onNavigateToNotificationFilter: () -> Unit = {}
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val clipboardContent by viewModel.clipboardContent.collectAsState()
    val incomingClipboard by viewModel.incomingClipboard.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "pulseScale"
    )

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(listOf(Cyan400, Blue500))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Hub, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Text("Synapse", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                },
                actions = {
                    StatusPill(connectionStatus)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Error
            item {
                AnimatedVisibility(visible = errorMessage != null, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
                    GlassCard(containerColor = Red400.copy(alpha = 0.12f), borderColor = Red400.copy(alpha = 0.3f)) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ErrorOutline, null, tint = Red400)
                            Spacer(Modifier.width(10.dp))
                            Text(errorMessage ?: "", modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                            IconButton(onClick = { viewModel.clearError() }) { Icon(Icons.Filled.Close, "Kapat", tint = Color.White.copy(alpha = 0.5f)) }
                        }
                    }
                }
            }

            // Status Hero
            item {
                StatusHeroCard(
                    status = connectionStatus, device = connectedDevice, pulseScale = pulseScale,
                    onDiscover = { viewModel.startDiscovery() }, onStop = { viewModel.stopDiscovery() },
                    onDisconnect = { viewModel.disconnect() }, onPair = onNavigateToPairing
                )
            }

            // Connected features
            if (connectionStatus == ConnectionStatus.CONNECTED) {
                // Quick Stats
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatChip(Modifier.weight(1f), Icons.Filled.ContentPaste, "Pano", if (clipboardContent.isNotBlank()) "Aktif" else "—", Cyan400)
                        StatChip(Modifier.weight(1f), Icons.Filled.Notifications, "Bildirim", "Açık", Amber400)
                        StatChip(Modifier.weight(1f), Icons.Filled.Wifi, "Bağlantı", "Kararlı", Green400)
                    }
                }

                // Clipboard
                item { ClipboardCard(clipboardContent, incomingClipboard) }

                // Settings row
                item {
                    GlassCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(onClick = onNavigateToNotificationFilter) {
                                Icon(Icons.Filled.Notifications, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Bildirim Ayarları", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Status Pill

@Composable
fun StatusPill(status: ConnectionStatus) {
    val (color, label) = when (status) {
        ConnectionStatus.CONNECTED -> Green400 to "Bağlı"
        ConnectionStatus.DISCOVERING -> Amber400 to "Arıyor"
        ConnectionStatus.PAIRING -> Blue500 to "Eşleşiyor"
        ConnectionStatus.ERROR -> Red400 to "Hata"
        ConnectionStatus.DISCONNECTED -> Color.Gray to "Çevrimdışı"
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

// MARK: - Status Hero

@Composable
fun StatusHeroCard(
    status: ConnectionStatus, device: Device?, pulseScale: Float,
    onDiscover: () -> Unit, onStop: () -> Unit, onDisconnect: () -> Unit, onPair: () -> Unit
) {
    GlassCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Animated icon
            Box(
                modifier = Modifier.size(72.dp)
                    .then(if (status == ConnectionStatus.DISCOVERING) Modifier.scale(pulseScale) else Modifier)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            when (status) {
                                ConnectionStatus.CONNECTED -> listOf(Green400.copy(0.25f), Green400.copy(0.05f))
                                ConnectionStatus.DISCOVERING -> listOf(Amber400.copy(0.25f), Amber400.copy(0.05f))
                                else -> listOf(Color.Gray.copy(0.15f), Color.Gray.copy(0.03f))
                            }
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (status) {
                        ConnectionStatus.CONNECTED -> Icons.Filled.LinkOff
                        ConnectionStatus.DISCOVERING -> Icons.Filled.Radar
                        ConnectionStatus.PAIRING -> Icons.Filled.QrCode2
                        else -> Icons.Filled.WifiFind
                    },
                    null, modifier = Modifier.size(30.dp),
                    tint = when (status) {
                        ConnectionStatus.CONNECTED -> Green400
                        ConnectionStatus.DISCOVERING -> Amber400
                        else -> Color.White.copy(0.6f)
                    }
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                when (status) {
                    ConnectionStatus.CONNECTED -> "Bağlandı"
                    ConnectionStatus.DISCOVERING -> "Aranıyor..."
                    ConnectionStatus.PAIRING -> "Eşleşiyor..."
                    ConnectionStatus.ERROR -> "Hata"
                    ConnectionStatus.DISCONNECTED -> "Bağlantı Yok"
                },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            if (device != null) {
                Text("${device.name}", fontSize = 13.sp, color = Color.White.copy(0.5f))
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (status) {
                    ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR -> {
                        ActionButton("Keşfet", Icons.Filled.Search, Cyan400, onClick = onDiscover)
                        ActionButton("QR Eşleş", Icons.Filled.QrCode, Blue500, onClick = onPair)
                    }
                    ConnectionStatus.DISCOVERING -> {
                        ActionButton("Durdur", Icons.Filled.Close, Color.Gray, onClick = onStop)
                    }
                    ConnectionStatus.CONNECTED -> {
                        ActionButton("Bağlantıyı Kes", Icons.Filled.LinkOff, Red400, onClick = onDisconnect)
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun ActionButton(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tint.copy(alpha = 0.15f), contentColor = tint),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// MARK: - Stat Chip

@Composable
fun StatChip(modifier: Modifier, icon: ImageVector, label: String, value: String, tint: Color) {
    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(label, fontSize = 10.sp, color = Color.White.copy(0.4f))
        }
    }
}

// MARK: - Clipboard Card

@Composable
fun ClipboardCard(sent: String, received: String?) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ContentPaste, null, tint = Cyan400, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pano Senkronizasyonu", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
            }
            Spacer(Modifier.height(12.dp))

            if (sent.isNotBlank()) {
                ClipItem("Gönderilen", sent, Icons.Filled.ArrowUpward, Green400)
                Spacer(Modifier.height(8.dp))
            }
            if (received != null) {
                ClipItem("Alınan", received, Icons.Filled.ArrowDownward, Blue500)
            }
            if (sent.isBlank() && received == null) {
                Text("Kopyalanan metin otomatik senkronize edilir", fontSize = 12.sp, color = Color.White.copy(0.35f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun ClipItem(direction: String, content: String, icon: ImageVector, color: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.08f)) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(direction, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color)
                Text(content, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White.copy(0.7f))
            }
        }
    }
}

// MARK: - Device Card (for discovered list)

@Composable
fun DeviceCard(device: Device, onPair: () -> Unit) {
    GlassCard {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Cyan400.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (device.deviceType == "macOS") Icons.Filled.Laptop else Icons.Filled.PhoneAndroid,
                    null, tint = Cyan400, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                Text("${device.ipAddress}:${device.port}", fontSize = 11.sp, color = Color.White.copy(0.4f))
            }
            IconButton(onClick = onPair) {
                Icon(Icons.Filled.ChevronRight, "Eşleş", tint = Color.White.copy(0.3f))
            }
        }
    }
}

// MARK: - Glass Card Component

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    containerColor: Color = CardDark,
    borderColor: Color = CardBorder,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, borderColor)
    ) {
        content()
    }
}
