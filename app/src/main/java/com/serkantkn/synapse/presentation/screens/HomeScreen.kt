package com.serkantkn.synapse.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.shapes.RoundedRectangle
import com.serkantkn.synapse.domain.model.ConnectionStatus
import com.serkantkn.synapse.domain.model.Device
import com.serkantkn.synapse.presentation.viewmodel.SynapseViewModel
import com.serkantkn.synapse.ui.glass.GlassButton
import com.serkantkn.synapse.ui.glass.GlassCard
import com.serkantkn.synapse.ui.glass.GlassIconButton
import com.serkantkn.synapse.ui.glass.GlassScaffold
import com.serkantkn.synapse.ui.glass.GlassTopBar
import com.serkantkn.synapse.ui.glass.GradientIcon
import com.serkantkn.synapse.ui.glass.GroupedRow
import com.serkantkn.synapse.ui.glass.IconTile
import com.serkantkn.synapse.ui.glass.LargeTitle
import com.serkantkn.synapse.ui.glass.RowPosition
import com.serkantkn.synapse.ui.glass.SecondaryText
import com.serkantkn.synapse.ui.glass.SectionHeader
import com.serkantkn.synapse.ui.glass.StatusBadge
import com.serkantkn.synapse.ui.glass.StepRow
import com.serkantkn.synapse.ui.glass.glass
import com.serkantkn.synapse.ui.glass.tint
import com.serkantkn.synapse.ui.theme.Apple
import com.serkantkn.synapse.ui.theme.AppleType
import com.serkantkn.synapse.ui.theme.SynapseBlue
import com.serkantkn.synapse.ui.theme.SynapseCyan

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
    val platform by viewModel.targetPlatform.collectAsState()
    val peer = if (platform == "iOS") "iPhone" else "Mac"

    val context = LocalContext.current
    var isAccessibilityActive by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityActive = com.serkantkn.synapse.data.service.SynapseAccessibilityService.instance != null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val listState = rememberLazyListState()
    val showTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 }
    }
    val isConnected = connectionStatus == ConnectionStatus.CONNECTED

    GlassScaffold(
        topBar = {
            GlassTopBar(title = "Synapse", showTitle = showTitle) {
                StatusBadge(connectionStatus)
            }
        }
    ) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding(),
                bottom = bottom + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { LargeTitle("Synapse", subtitle = if (platform == "iOS") "iPhone / iPad ile" else "Mac ile") }

            item {
                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    ErrorCard(errorMessage.orEmpty(), onDismiss = { viewModel.clearError() })
                }
            }

            item {
                StatusHeroCard(
                    status = connectionStatus,
                    device = connectedDevice,
                    peer = peer,
                    onDiscover = { viewModel.startDiscovery() },
                    onStop = { viewModel.stopDiscovery() },
                    onDisconnect = { viewModel.disconnect() },
                    onPair = onNavigateToPairing
                )
            }

            if (!isAccessibilityActive && platform != "iOS") {
                item {
                    AccessibilityCard(onEnable = {
                        try {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: Exception) {
                        }
                    })
                }
            }

            if (isConnected) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(Modifier.weight(1f), Icons.Rounded.ContentPaste, "Pano",
                            if (clipboardContent.isNotBlank()) "Etkin" else "—", SynapseCyan)
                        StatTile(Modifier.weight(1f), Icons.Rounded.Notifications, "Bildirim", "Açık", Apple.colors.orange)
                        StatTile(Modifier.weight(1f), Icons.Rounded.Lock, "Bağlantı", "Şifreli", Apple.colors.green)
                    }
                }
                item { ClipboardCard(clipboardContent, incomingClipboard) }
                item {
                    Column {
                        SectionHeader("Ayarlar")
                        GroupedRow(
                            position = RowPosition.Single,
                            onClick = onNavigateToNotificationFilter,
                            leading = { IconTile(Icons.Rounded.Notifications, Apple.colors.red, size = 32.dp) }
                        ) {
                            Text("Bildirim Ayarları", style = AppleType.body, color = Apple.colors.label)
                            SecondaryText("$peer'e iletilecek uygulamalar", style = AppleType.footnote)
                        }
                    }
                }
            } else if (connectionStatus != ConnectionStatus.PAIRING) {
                item { HowToCard(platform) }
            }
        }
    }
}

// MARK: - Status hero

@Composable
private fun StatusHeroCard(
    status: ConnectionStatus,
    device: Device?,
    peer: String,
    onDiscover: () -> Unit,
    onStop: () -> Unit,
    onDisconnect: () -> Unit,
    onPair: () -> Unit,
) {
    val colors = Apple.colors
    GlassCard(cornerRadius = 30.dp, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 26.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BeaconOrb(
                icon = when (status) {
                    ConnectionStatus.CONNECTED -> if (peer == "iPhone") Icons.Rounded.PhoneIphone else Icons.Rounded.Laptop
                    ConnectionStatus.DISCOVERING -> Icons.Rounded.Radar
                    ConnectionStatus.PAIRING -> Icons.Rounded.Key
                    ConnectionStatus.ERROR -> Icons.Rounded.ErrorOutline
                    ConnectionStatus.DISCONNECTED -> Icons.Rounded.WifiFind
                },
                tint = status.tint(),
                rippling = status == ConnectionStatus.DISCOVERING
            )
            Spacer(Modifier.height(18.dp))
            Text(
                when (status) {
                    ConnectionStatus.CONNECTED -> "Bağlandı"
                    ConnectionStatus.DISCOVERING -> "$peer aranıyor…"
                    ConnectionStatus.PAIRING -> "Eşleşiliyor…"
                    ConnectionStatus.ERROR -> "Bağlanamadı"
                    ConnectionStatus.DISCONNECTED -> "Bağlantı yok"
                },
                style = AppleType.title2,
                color = colors.label
            )
            Spacer(Modifier.height(4.dp))
            SecondaryText(
                when (status) {
                    ConnectionStatus.CONNECTED -> device?.name?.let { "$it · uçtan uca şifreli" } ?: "Uçtan uca şifreli"
                    ConnectionStatus.DISCOVERING -> "$peer aynı Wi-Fi ağındaysa birazdan bulunur."
                    ConnectionStatus.PAIRING -> "Güvenli bağlantı kuruluyor"
                    ConnectionStatus.ERROR -> "Tekrar aramayı ya da eşleştirmeyi dene."
                    ConnectionStatus.DISCONNECTED -> "Aramayı başlat ya da QR kodla eşleştir."
                },
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                when (status) {
                    ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR -> {
                        GlassButton("Ara", onDiscover, Modifier.weight(1f), icon = Icons.Rounded.Search, prominent = true)
                        GlassButton("Eşleştir", onPair, Modifier.weight(1f), icon = Icons.Rounded.QrCodeScanner)
                    }
                    ConnectionStatus.DISCOVERING -> {
                        GlassButton("Durdur", onStop, Modifier.weight(1f), icon = Icons.Rounded.Close, tint = colors.label)
                        GlassButton("Eşleştir", onPair, Modifier.weight(1f), icon = Icons.Rounded.QrCodeScanner)
                    }
                    ConnectionStatus.CONNECTED -> {
                        GlassButton("Bağlantıyı Kes", onDisconnect, Modifier.weight(1f), icon = Icons.Rounded.LinkOff, tint = colors.red)
                    }
                    ConnectionStatus.PAIRING -> Unit
                }
            }
        }
    }
}

/** Glass orb with the state icon; rings ripple outwards while searching. */
@Composable
private fun BeaconOrb(icon: ImageVector, tint: Color, rippling: Boolean) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        if (rippling) {
            Canvas(Modifier.fillMaxSize()) {
                val base = size.minDimension * 0.33f
                repeat(3) { index ->
                    val t = (phase + index / 3f) % 1f
                    drawCircle(
                        color = SynapseCyan.copy(alpha = (1f - t) * 0.55f),
                        radius = base * (1f + t * 0.5f),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
        Box(
            Modifier.size(100.dp).glass(CircleShape, tint = tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            GradientIcon(icon, Modifier.size(42.dp))
        }
    }
}

// MARK: - Cards

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    val colors = Apple.colors
    GlassCard(tint = colors.red.copy(alpha = 0.14f), contentPadding = PaddingValues(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.red)
            Spacer(Modifier.width(12.dp))
            Text(message, style = AppleType.subheadline, color = colors.label, modifier = Modifier.weight(1f))
            GlassIconButton(Icons.Rounded.Close, "Kapat", onDismiss, size = 36.dp, tint = colors.secondaryLabel)
        }
    }
}

@Composable
private fun AccessibilityCard(onEnable: () -> Unit) {
    val colors = Apple.colors
    GlassCard(tint = colors.orange.copy(alpha = 0.12f)) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(Icons.Rounded.AccessibilityNew, colors.orange, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Erişilebilirlik servisi kapalı", style = AppleType.headline, color = colors.label)
                Spacer(Modifier.height(4.dp))
                SecondaryText(
                    "Mac'ten ekran kontrolü, klavye ve sürükle-bırak dosya gönderimi için Synapse erişilebilirlik servisini aç.",
                    style = AppleType.footnote
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        GlassButton("Şimdi Etkinleştir", onEnable, prominent = true, tint = colors.orange, large = false)
    }
}

@Composable
private fun StatTile(modifier: Modifier, icon: ImageVector, label: String, value: String, tint: Color) {
    GlassCard(modifier = modifier, cornerRadius = 22.dp, contentPadding = PaddingValues(14.dp)) {
        IconTile(icon, tint, size = 30.dp)
        Spacer(Modifier.height(10.dp))
        Text(value, style = AppleType.headline, color = Apple.colors.label, maxLines = 1)
        Text(label, style = AppleType.caption, color = Apple.colors.secondaryLabel)
    }
}

@Composable
private fun ClipboardCard(sent: String, received: String?) {
    val colors = Apple.colors
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.ContentPaste, SynapseCyan, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Text("Pano Senkronizasyonu", style = AppleType.headline, color = colors.label)
        }
        Spacer(Modifier.height(12.dp))
        if (sent.isNotBlank()) {
            ClipItem("Gönderilen", sent, Icons.Rounded.ArrowUpward, colors.green)
        }
        if (received != null) {
            if (sent.isNotBlank()) Spacer(Modifier.height(8.dp))
            ClipItem("Alınan", received, Icons.Rounded.ArrowDownward, SynapseBlue)
        }
        if (sent.isBlank() && received == null) {
            SecondaryText("Kopyaladığın metin otomatik olarak eşitlenir.", style = AppleType.footnote)
        }
    }
}

@Composable
private fun ClipItem(direction: String, content: String, icon: ImageVector, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Apple.colors.fill.copy(alpha = 0.5f), RoundedRectangle(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(direction, style = AppleType.caption.copy(fontWeight = FontWeight.SemiBold), color = color)
            Text(content, style = AppleType.subheadline, color = Apple.colors.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HowToCard(platform: String) {
    GlassCard {
        Text("Nasıl bağlanır?", style = AppleType.headline, color = Apple.colors.label)
        Spacer(Modifier.height(14.dp))
        if (platform == "iOS") {
            StepRow(1, "iPhone'da Synapse'i aç", "\"Yayını Başlat\"a dokun.")
            Spacer(Modifier.height(14.dp))
            StepRow(2, "Eşleştir'e dokun", "Listeden iPhone'u seç.")
            Spacer(Modifier.height(14.dp))
            StepRow(3, "PIN kodunu gir", "iPhone ekranındaki 6 haneli kod. Bir kez yeterli.")
        } else {
            StepRow(1, "Mac'te Synapse'i aç", "Menü çubuğunda Synapse simgesi görünür.")
            Spacer(Modifier.height(14.dp))
            StepRow(2, "Eşleştir'e dokun", "Mac'teki QR kodu tara ya da listeden seç.")
            Spacer(Modifier.height(14.dp))
            StepRow(3, "PIN kodunu gir", "Mac ekranındaki 6 haneli kod. Bir kez yeterli.")
        }
    }
}
