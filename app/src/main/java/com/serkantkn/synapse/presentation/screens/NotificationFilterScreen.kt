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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.shapes.RoundedRectangle
import com.serkantkn.synapse.ui.glass.GlassButton
import com.serkantkn.synapse.ui.glass.GlassCard
import com.serkantkn.synapse.ui.glass.GlassScaffold
import com.serkantkn.synapse.ui.glass.GlassTopBar
import com.serkantkn.synapse.ui.glass.GroupedRow
import com.serkantkn.synapse.ui.glass.IconTile
import com.serkantkn.synapse.ui.glass.IosSwitch
import com.serkantkn.synapse.ui.glass.LargeTitle
import com.serkantkn.synapse.ui.glass.RowPosition
import com.serkantkn.synapse.ui.glass.SecondaryText
import com.serkantkn.synapse.ui.glass.SectionHeader
import com.serkantkn.synapse.ui.glass.rowPosition
import com.serkantkn.synapse.ui.theme.Apple
import com.serkantkn.synapse.ui.theme.AppleType

data class AppFilterItem(
    val packageName: String,
    val appName: String,
    val icon: Bitmap?,
    val isEnabled: Boolean
)

@Composable
fun NotificationFilterScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("notification_filter_prefs", Context.MODE_PRIVATE) }
    val colors = Apple.colors

    // Re-checked on resume: the user may grant access in Settings and come back.
    var isListenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isListenerEnabled = isNotificationListenerEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Load installed apps
    val apps = remember { loadInstalledApps(context, prefs) }
    var filterState by remember { mutableStateOf(apps) }

    val listState = rememberLazyListState()
    val showTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 }
    }

    GlassScaffold(
        topBar = { GlassTopBar(title = "Bildirimler", showTitle = showTitle, onBack = onBack) }
    ) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding(), bottom = bottom + 32.dp),
        ) {
            item {
                LargeTitle("Bildirimler", subtitle = "Seçtiğin uygulamaların bildirimleri Mac'e iletilir.")
                Spacer(Modifier.height(18.dp))
            }

            if (!isListenerEnabled) {
                item {
                    GlassCard(tint = colors.orange.copy(alpha = 0.12f)) {
                        Row(verticalAlignment = Alignment.Top) {
                            IconTile(Icons.Rounded.Warning, colors.orange, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Bildirim erişimi gerekli", style = AppleType.headline, color = colors.label)
                                SecondaryText("Bildirimleri Mac'e iletebilmek için Synapse'e bildirim erişimi ver.", style = AppleType.footnote)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        GlassButton(
                            "İzin Ver",
                            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            prominent = true,
                            tint = colors.orange,
                            large = false
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        "${filterState.count { it.isEnabled }} / ${filterState.size} uygulama",
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton("Tümü", onClick = {
                        filterState = filterState.map { it.copy(isEnabled = true) }
                        saveFilter(prefs, filterState)
                    }, large = false)
                    Spacer(Modifier.width(8.dp))
                    GlassButton("Hiçbiri", onClick = {
                        filterState = filterState.map { it.copy(isEnabled = false) }
                        saveFilter(prefs, filterState)
                    }, large = false)
                }
                Spacer(Modifier.height(8.dp))
            }

            itemsIndexed(filterState, key = { _, app -> app.packageName }) { index, app ->
                AppFilterRow(
                    app = app,
                    position = rowPosition(index, filterState.size),
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

@Composable
private fun AppFilterRow(app: AppFilterItem, position: RowPosition, onToggle: (Boolean) -> Unit) {
    GroupedRow(
        position = position,
        leading = {
            app.icon?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(RoundedRectangle(8.dp))
                )
            } ?: IconTile(Icons.Rounded.Notifications, Apple.colors.gray, size = 32.dp)
        },
        trailing = { IosSwitch(checked = app.isEnabled, onCheckedChange = onToggle) }
    ) {
        Text(
            text = app.appName,
            style = AppleType.body,
            color = Apple.colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
