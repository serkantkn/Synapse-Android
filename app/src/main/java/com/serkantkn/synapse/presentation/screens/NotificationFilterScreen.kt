package com.serkantkn.synapse.presentation.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class AppFilterItem(
    val packageName: String,
    val appName: String,
    val icon: Bitmap?,
    val isEnabled: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationFilterScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("notification_filter_prefs", Context.MODE_PRIVATE) }

    // Check if notification listener is enabled
    val isListenerEnabled = remember {
        mutableStateOf(isNotificationListenerEnabled(context))
    }

    // Load installed apps
    val apps = remember {
        loadInstalledApps(context, prefs)
    }

    var filterState by remember { mutableStateOf(apps) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bildirim Ayarları") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Notification Access Warning
            if (!isListenerEnabled.value) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Bildirim Erişimi Gerekli",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Bildirimleri Mac'e iletebilmek için erişim izni vermeniz gerekiyor.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        ) {
                            Text("İzin Ver")
                        }
                    }
                }
            }

            // Info card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Seçtiğiniz uygulamaların bildirimleri Mac'inize iletilecektir.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Select All / Deselect All
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${filterState.count { it.isEnabled }} / ${filterState.size} uygulama seçili",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        filterState = filterState.map { it.copy(isEnabled = true) }
                        saveFilter(prefs, filterState)
                    }) { Text("Tümünü Seç") }
                    TextButton(onClick = {
                        filterState = filterState.map { it.copy(isEnabled = false) }
                        saveFilter(prefs, filterState)
                    }) { Text("Temizle") }
                }
            }

            // App list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filterState, key = { it.packageName }) { app ->
                    AppFilterRow(
                        app = app,
                        onToggle = { enabled ->
                            filterState = filterState.map {
                                if (it.packageName == app.packageName) it.copy(isEnabled = enabled) else it
                            }
                            saveFilter(prefs, filterState)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppFilterRow(app: AppFilterItem, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // App Icon
        app.icon?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier.size(40.dp).clip(CircleShape)
            )
        } ?: Box(
            modifier = Modifier.size(40.dp).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Notifications, contentDescription = null)
        }

        // App name
        Text(
            text = app.appName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Toggle
        Switch(
            checked = app.isEnabled,
            onCheckedChange = onToggle
        )
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return enabledListeners?.contains(context.packageName) == true
}

private fun loadInstalledApps(context: Context, prefs: android.content.SharedPreferences): List<AppFilterItem> {
    val pm = context.packageManager
    val allowedSet = prefs.getStringSet("allowed_packages", null)

    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
        .filter { it.packageName != context.packageName }
        .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        .map { appInfo ->
            val icon = try {
                val drawable = pm.getApplicationIcon(appInfo)
                if (drawable is BitmapDrawable) {
                    Bitmap.createScaledBitmap(drawable.bitmap, 48, 48, true)
                } else {
                    val b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(b)
                    drawable.setBounds(0, 0, 48, 48)
                    drawable.draw(canvas)
                    b
                }
            } catch (_: Exception) { null }

            AppFilterItem(
                packageName = appInfo.packageName,
                appName = pm.getApplicationLabel(appInfo).toString(),
                icon = icon,
                isEnabled = allowedSet?.contains(appInfo.packageName) ?: true
            )
        }
}

private fun saveFilter(prefs: android.content.SharedPreferences, apps: List<AppFilterItem>) {
    val enabledSet = apps.filter { it.isEnabled }.map { it.packageName }.toSet()
    prefs.edit().putStringSet("allowed_packages", enabledSet).apply()
}
