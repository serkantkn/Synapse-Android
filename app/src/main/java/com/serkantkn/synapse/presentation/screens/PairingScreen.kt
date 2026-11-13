package com.serkantkn.synapse.presentation.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.serkantkn.synapse.domain.model.ConnectionStatus
import com.serkantkn.synapse.domain.model.Device
import com.serkantkn.synapse.presentation.viewmodel.SynapseViewModel
import com.serkantkn.synapse.ui.glass.CenteredMessage
import com.serkantkn.synapse.ui.glass.GlassButton
import com.serkantkn.synapse.ui.glass.GlassCard
import com.serkantkn.synapse.ui.glass.GlassScaffold
import com.serkantkn.synapse.ui.glass.GlassSegmentedControl
import com.serkantkn.synapse.ui.glass.GlassTopBar
import com.serkantkn.synapse.ui.glass.GroupedRow
import com.serkantkn.synapse.ui.glass.IconTile
import com.serkantkn.synapse.ui.glass.LargeTitle
import com.serkantkn.synapse.ui.glass.SecondaryText
import com.serkantkn.synapse.ui.glass.SectionHeader
import com.serkantkn.synapse.ui.glass.glass
import com.serkantkn.synapse.ui.glass.rowPosition
import com.serkantkn.synapse.ui.theme.Apple
import com.serkantkn.synapse.ui.theme.AppleType
import com.serkantkn.synapse.ui.theme.SynapseBlue
import com.serkantkn.synapse.ui.theme.SynapseCyan
import java.util.concurrent.Executors

@Composable
fun PairingScreen(
    viewModel: SynapseViewModel,
    onBack: () -> Unit
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val platform by viewModel.targetPlatform.collectAsState()
    val peer = if (platform == "iOS") "iPhone" else "Mac"
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // The iPhone app shows a PIN, not a QR code, so the list is the starting point there.
    var showPinEntry by remember { mutableStateOf(platform == "iOS") }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var enteredPin by remember { mutableStateOf("") }
    var enteredIp by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
        }
    )

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        launcher.launch(permissions.toTypedArray())
        viewModel.startDiscovery()
    }

    // Navigate back when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            onBack()
        }
    }

    val scroll = rememberScrollState()
    val showTitle = scroll.value > with(LocalDensity.current) { 44.dp.toPx() }
    val inPinStep = showPinEntry && (selectedDevice != null || showAdvanced)

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "Eşleştir",
                showTitle = showTitle,
                onBack = {
                    if (inPinStep) {
                        selectedDevice = null
                        showAdvanced = false
                        enteredPin = ""
                    } else onBack()
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            LargeTitle("Eşleştir", subtitle = "$peer ile güvenli bağlantı")

            if (!inPinStep) {
                GlassSegmentedControl(
                    options = listOf("QR Tara", "Cihaz Listesi"),
                    selectedIndex = if (showPinEntry) 1 else 0,
                    onSelect = { index ->
                        showPinEntry = index == 1
                        if (index == 0) selectedDevice = null
                    }
                )
            }

            // Pairing failures (wrong PIN, rejected token...) are reported here, not only on Home.
            errorMessage?.let { msg ->
                GlassCard(tint = Apple.colors.red.copy(alpha = 0.14f), contentPadding = PaddingValues(14.dp)) {
                    Text(msg, style = AppleType.subheadline, color = Apple.colors.label, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                }
            }

            AnimatedContent(
                targetState = Triple(showPinEntry, inPinStep, hasCameraPermission),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "mode"
            ) { (isList, pinStep, camera) ->
                when {
                    pinStep -> PinEntrySection(
                        targetName = selectedDevice?.name ?: "Elle bağlantı",
                        peer = peer,
                        enteredPin = enteredPin,
                        enteredIp = enteredIp,
                        showAdvanced = showAdvanced,
                        onPinChange = { enteredPin = it },
                        onIpChange = { enteredIp = it },
                        onConnect = { pin, ip ->
                            if (ip.isNotEmpty()) {
                                viewModel.pairWithIp(ip, pin)
                            } else if (selectedDevice != null) {
                                viewModel.setPairingCode(pin)
                                viewModel.pairWithDevice(selectedDevice!!)
                            }
                        }
                    )
                    isList -> DeviceListSection(
                        devices = discoveredDevices,
                        peer = peer,
                        onDeviceSelected = { selectedDevice = it },
                        onManualIpClick = { showAdvanced = true }
                    )
                    camera -> QrScannerSection(
                        onQrDetected = { url -> parseAndConnect(url, viewModel) }
                    )
                    else -> CenteredMessage(
                        icon = Icons.Rounded.CameraAlt,
                        title = "Kamera izni gerekli",
                        message = "QR kodu taramak için kamera iznini ver ya da Cihaz Listesi'ni kullan."
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceListSection(
    devices: List<Device>,
    peer: String,
    onDeviceSelected: (Device) -> Unit,
    onManualIpClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (devices.isEmpty()) {
            GlassCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = Apple.colors.secondaryLabel,
                        strokeCap = StrokeCap.Round
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("$peer aranıyor…", style = AppleType.headline, color = Apple.colors.label)
                    Spacer(Modifier.height(4.dp))
                    SecondaryText(
                        "Aynı Wi-Fi ağında olduğunuzdan emin ol. Bulunamazsa IP adresiyle bağlanabilirsin.",
                        style = AppleType.footnote,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column {
                SectionHeader("Ağdaki cihazlar")
                devices.forEachIndexed { index, device ->
                    GroupedRow(
                        position = rowPosition(index, devices.size),
                        onClick = { onDeviceSelected(device) },
                        leading = {
                            IconTile(
                                if (device.deviceType == "macOS") Icons.Rounded.Laptop else Icons.Rounded.PhoneIphone,
                                SynapseBlue,
                                size = 36.dp
                            )
                        }
                    ) {
                        Text(device.name, style = AppleType.body.copy(fontWeight = FontWeight.Medium), color = Apple.colors.label)
                        SecondaryText("${device.ipAddress}:${device.port}", style = AppleType.footnote)
                    }
                }
            }
        }

        GlassButton(
            "IP Adresiyle Bağlan",
            onManualIpClick,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Rounded.Wifi,
            prominent = devices.isEmpty()
        )
    }
}

@Composable
fun PinEntrySection(
    targetName: String,
    peer: String,
    enteredPin: String,
    enteredIp: String,
    showAdvanced: Boolean,
    onPinChange: (String) -> Unit,
    onIpChange: (String) -> Unit,
    onConnect: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        GlassCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconTile(Icons.Rounded.Link, SynapseBlue, size = 48.dp)
                Spacer(Modifier.height(12.dp))
                Text(targetName, style = AppleType.title3, color = Apple.colors.label, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                SecondaryText("$peer ekranındaki 6 haneli PIN kodunu gir.", style = AppleType.footnote, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                PinField(value = enteredPin, onValueChange = onPinChange)
            }
        }

        if (showAdvanced) {
            GlassTextField(
                value = enteredIp,
                onValueChange = onIpChange,
                placeholder = "$peer IP adresi (ör. 192.168.1.14)",
                keyboardType = KeyboardType.Decimal
            )
        }

        GlassButton(
            "Bağlan",
            onClick = { onConnect(enteredPin, enteredIp) },
            modifier = Modifier.fillMaxWidth(),
            prominent = true,
            enabled = enteredPin.length == 6 && (!showAdvanced || enteredIp.isNotBlank())
        )
    }
}

/** Six digit boxes in two groups of three, like the PIN on the Mac and iPhone. */
@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val colors = Apple.colors
    BasicTextField(
        value = value,
        onValueChange = { new -> if (new.length <= 6 && new.all { it.isDigit() }) onValueChange(new) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = AppleType.body.copy(color = Color.Transparent),
        modifier = Modifier
            .focusRequester(focus)
            .semantics { contentDescription = "PIN kodu" },
        decorationBox = { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (group in 0..1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in 0..2) {
                            val index = group * 3 + i
                            val digit = value.getOrNull(index)
                            val active = index == value.length
                            Box(
                                Modifier
                                    .size(width = 42.dp, height = 54.dp)
                                    .background(colors.fill, RoundedRectangle(12.dp))
                                    .border(
                                        width = if (active) 2.dp else 0.dp,
                                        color = if (active) colors.blue else Color.Transparent,
                                        shape = RoundedRectangle(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    digit?.toString() ?: "",
                                    style = AppleType.title1.copy(fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
                                    color = colors.label
                                )
                            }
                        }
                    }
                }
            }
        }
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
) {
    val colors = Apple.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .glass(Capsule())
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = AppleType.body, color = colors.tertiaryLabel)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = AppleType.body.copy(color = colors.label),
            cursorBrush = SolidColor(colors.blue),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder }
        )
    }
}

@Composable
fun QrScannerSection(onQrDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var isDetected by remember { mutableStateOf(false) }

    Column {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .glass(RoundedRectangle(36.dp))
            .padding(8.dp)
            .clip(RoundedRectangle(30.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(1280, 720))
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                processImageProxy(imageProxy, onQrDetected = { url ->
                                    if (!isDetected) {
                                        isDetected = true
                                        onQrDetected(url)
                                    }
                                })
                            }
                        }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
                    } catch (e: Exception) { Log.e("Scanner", "Binding failed", e) }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
        ScanBrackets(Modifier.fillMaxSize(0.62f))
    }
    SecondaryText(
        "Mac'teki Synapse penceresinde görünen QR kodu çerçeveye hizala.",
        style = AppleType.footnote,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    )
    }
}

/** Four corner brackets marking where the QR code should go. */
@Composable
private fun ScanBrackets(modifier: Modifier) {
    Canvas(modifier) {
        val arm = size.minDimension * 0.18f
        val stroke = 4.dp.toPx()
        val color = Color.White.copy(alpha = 0.9f)
        val corners = listOf(
            Offset(0f, 0f) to Offset(1f, 1f),
            Offset(size.width, 0f) to Offset(-1f, 1f),
            Offset(0f, size.height) to Offset(1f, -1f),
            Offset(size.width, size.height) to Offset(-1f, -1f),
        )
        corners.forEach { (corner, dir) ->
            drawLine(color, corner, corner + Offset(arm * dir.x, 0f), stroke, StrokeCap.Round)
            drawLine(color, corner, corner + Offset(0f, arm * dir.y), stroke, StrokeCap.Round)
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processImageProxy(imageProxy: ImageProxy, onQrDetected: (String) -> Unit) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let { value ->
                        if (value.startsWith("synapse://")) onQrDetected(value)
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    } else { imageProxy.close() }
}

private fun parseAndConnect(url: String, viewModel: SynapseViewModel) {
    try {
        val uri = android.net.Uri.parse(url)
        val ip = uri.getQueryParameter("ip") ?: return
        val port = uri.getQueryParameter("port")?.toInt() ?: return
        val code = uri.getQueryParameter("code") ?: return
        viewModel.setPairingCode(code)
        val device = Device(id = "scanned_device", name = "Mac (QR)", ipAddress = ip, port = port, deviceType = "macOS")
        viewModel.pairWithDevice(device)
    } catch (e: Exception) { Log.e("Pairing", "Parse error", e) }
}
