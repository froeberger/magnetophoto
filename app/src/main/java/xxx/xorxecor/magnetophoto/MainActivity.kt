package xxx.xorxecor.magnetophoto

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.rememberImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xxx.xorxecor.magnetophoto.ui.theme.MagnetophotoTheme

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private var magneticField by mutableStateOf(floatArrayOf(0f, 0f, 0f))


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()


        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        setContent {
            MagnetophotoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MagnetoColorizerApp(
                        magneticField = magneticField,
                        modifier = Modifier.padding(innerPadding) // Apply innerPadding here
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magneticField = event.values.clone()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used in this example
    }
}



@Composable
fun MagnetoColorizerApp(
    magneticField: FloatArray,
    modifier: Modifier = Modifier
) {
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()


    // Launcher to take a picture and save it to the provided Uri
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            // Image captured successfully
            if (BuildConfig.DEBUG) {
                Log.d("MagnetoColorizerApp", "Image captured successfully: $imageUri")
            }
        } else {
            // Handle capture failure
            if (BuildConfig.DEBUG) {
                Log.e("MagnetoColorizerApp", "Image capture failed")
            }
        }
    }


    // Function to generate Uri and launch camera
    fun launchCamera() {
        scope.launch(Dispatchers.IO) {
            imageUri = ComposeFileProvider.getImageUri(context)
        }.invokeOnCompletion {
            imageUri?.let { uri ->
                cameraLauncher.launch(uri)
            }
        }
    }

    // Launcher to request camera permission
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, launch camera
            launchCamera()
        } else {
            // Handle permission denied
            if (BuildConfig.DEBUG) {
                Log.e("MagnetoColorizerApp", "Camera permission denied")
            }
        }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = {
                when (PackageManager.PERMISSION_GRANTED) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) -> {
                        // Permission already granted, launch camera
                        scope.launch(Dispatchers.IO) {
                            imageUri = ComposeFileProvider.getImageUri(context)
                        }.invokeOnCompletion {
                            imageUri?.let { it1 -> cameraLauncher.launch(it1) }
                        }
                    }

                    else -> {
                        // Request permission
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        ) {
            Text("Take Photo")
        }

        Spacer(modifier = Modifier.height(16.dp))

        imageUri?.let { uri ->
            Image(
                painter = rememberImagePainter(uri),
                contentDescription = "Captured image",
                modifier = Modifier.size(300.dp),
                colorFilter = ColorFilter.colorMatrix(
                    ColorMatrix().apply {
                        setToSaturation(magneticField[0] / 100f)
                        // You can use magneticField[1] and magneticField[2] for other color adjustments
                    }
                )
            )
        }
    }
}