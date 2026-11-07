package com.serkantkn.synapse

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.serkantkn.synapse.data.service.SynapseService
import com.serkantkn.synapse.presentation.navigation.SynapseNavGraph
import com.serkantkn.synapse.ui.theme.SynapseTheme

class MainActivity : ComponentActivity() {

    private var synapseService: SynapseService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SynapseService.SynapseBinder
            synapseService = binder.getService()
            isBound = true
            Log.d("MainActivity", "✅ Service Bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synapseService = null
            isBound = false
            Log.d("MainActivity", "❌ Service Unbound")
        }
    }

    // Storage izni → duvar kağıdı için
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isGranted = permissions.entries.any { it.value }
        if (isGranted) {
            Log.d("MainActivity", "✅ Storage permission granted, requesting wallpaper from service")
            synapseService?.sendWallpaper()
        } else {
            Log.w("MainActivity", "⚠️ Storage permission denied by user")
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            synapseService?.onMediaProjectionGranted(result.resultCode, result.data!!)
        } else {
            Log.e("MainActivity", "❌ Screen capture permission denied")
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.serkantkn.synapse.ACTION_REQUEST_SCREEN_CAPTURE" -> {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                        val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                        projectionManager.createScreenCaptureIntent(config)
                    } else {
                        projectionManager.createScreenCaptureIntent()
                    }
                    screenCaptureLauncher.launch(captureIntent)
                }
                "com.serkantkn.synapse.ACTION_REQUEST_WALLPAPER_PERMISSION" -> {
                    requestStoragePermission()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Servisi başlat ve bağlan
        Intent(this, SynapseService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }

        // Broadcast receiver'ı kaydet
        val filter = IntentFilter().apply {
            addAction("com.serkantkn.synapse.ACTION_REQUEST_SCREEN_CAPTURE")
            addAction("com.serkantkn.synapse.ACTION_REQUEST_WALLPAPER_PERMISSION")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }

        // Storage iznini kontrol et ve gerekirse iste
        checkAndRequestStoragePermission()

        setContent {
            SynapseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SynapseNavGraph()
                }
            }
        }
    }

    private fun checkAndRequestStoragePermission() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }

        val needsRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsRequest) {
            Log.d("MainActivity", "📋 Requesting permissions: $permissions")
            storagePermissionLauncher.launch(permissions.toTypedArray())
        } else {
            Log.d("MainActivity", "✅ Storage permissions already granted")
        }
    }

    private fun requestStoragePermission() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        storagePermissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        unregisterReceiver(broadcastReceiver)
    }
}