package com.serkantkn.synapse

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
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

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Triggering service-side screen capture with granted permission
            synapseService?.onMediaProjectionGranted(result.resultCode, result.data!!)
        } else {
            Log.e("MainActivity", "❌ Screen capture permission denied")
        }
    }

    private val screenCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.serkantkn.synapse.ACTION_REQUEST_SCREEN_CAPTURE") {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Start and bind service
        Intent(this, SynapseService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }

        // Register receiver for screen capture requests from service
        val filter = IntentFilter("com.serkantkn.synapse.ACTION_REQUEST_SCREEN_CAPTURE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenCaptureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenCaptureReceiver, filter)
        }

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

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        unregisterReceiver(screenCaptureReceiver)
    }
}