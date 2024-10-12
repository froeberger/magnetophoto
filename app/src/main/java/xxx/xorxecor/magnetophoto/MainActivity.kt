package xxx.xorxecor.magnetophoto

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xxx.xorxecor.magnetophoto.ui.theme.MagnetophotoTheme
import java.io.InputStream

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
    var originalImageUri by remember { mutableStateOf<Uri?>(null) }
    var colorizedImageUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Function to load bitmap from Uri
    fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            Log.e("MagnetoColorizerApp", "Failed to load bitmap from Uri: $uri", e)
            null
        }
    }


    fun mapZAxisToColor(zValue: Float): android.graphics.Color {
        // Example mapping: blue to red based on zValue
        // Adjust the mapping logic as per your requirements
        val normalizedZ = ((zValue + 100f) / 200f).coerceIn(0f, 1f) // Normalize to [0,1]
        val red = (normalizedZ * 255).toInt()
        val blue = ((1f - normalizedZ) * 255).toInt()
        return android.graphics.Color.rgb(red, 0, blue).toColor()
    }

    // Function to colorize the bitmap
    fun colorizeBitmap(originalBitmap: Bitmap, color: android.graphics.Color): Bitmap {
        val bitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        val colorFilter = android.graphics.PorterDuffColorFilter(color.toArgb(), android.graphics.PorterDuff.Mode.SRC_ATOP)
        paint.colorFilter = colorFilter
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return bitmap
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            originalImageUri?.let { uri ->
                // Load the bitmap from the Uri
                val bitmap = loadBitmapFromUri(context, uri)
                bitmap?.let {
                    // Map z-axis to color
                    val color = mapZAxisToColor(magneticField.getOrElse(2) { 0f })

                    // Colorize the bitmap
                    val colorizedBitmap = colorizeBitmap(it, color)

                    // Save the colorized bitmap to a new Uri
                    colorizedImageUri = ComposeFileProvider.saveBitmapToUri(context, colorizedBitmap)
                }
            }
        } else {
            Log.e("MagnetoColorizerApp", "Image capture failed")
        }
    }


    // Function to launch camera
    fun launchCamera() {
        scope.launch(Dispatchers.IO) {
            originalImageUri = ComposeFileProvider.getImageUri(context)
            originalImageUri?.let { uri ->
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
                            colorizedImageUri= ComposeFileProvider.getImageUri(context)
                        }.invokeOnCompletion {
                            colorizedImageUri?.let { it1 -> cameraLauncher.launch(it1) }
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

        Spacer(modifier = Modifier.height(16.dp))

        colorizedImageUri?.let { uri ->
            Image(
                bitmap = loadBitmapFromUri(context, uri)?.asImageBitmap() ?: return@let,
                contentDescription = "Colorized Captured Image",
                modifier = Modifier
                    .size(300.dp)
                    .padding(8.dp)
            )
        }
    }
}