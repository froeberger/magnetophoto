package xxx.xorxecor.magnetophoto // Replace with your actual package name

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xxx.xorxecor.magnetophoto.ui.theme.MagnetophotoTheme
import java.io.IOException
import androidx.compose.ui.graphics.Color as ComposeColor

class MainActivity : ComponentActivity(), SensorEventListener2 {

    private lateinit var sensorManager: SensorManager

    // Define sensors
    private val gravitySensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) }
    private val magnetometerSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) }
    private val linearAccelSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) }
    private val gyroscopeSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    private val rotationVectorSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    private val proximitySensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) }
    private val ambientLightSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) }

    // Sensor data holders
    private var gravityData = FloatArray(3)
    private var magnetometerData = FloatArray(3)
    private var linearAccelData = FloatArray(3)
    private var gyroscopeData = FloatArray(3)
    private var rotationVectorData = FloatArray(3)
    private var proximityData = 0f
    private var ambientLightData = 0f

    // MutableSharedFlow to emit sensor data
    private val _sensorDataFlow = MutableSharedFlow<Pair<String, String>>()
    val sensorDataFlow = _sensorDataFlow.asSharedFlow()

    // CoroutineScope tied to the Activity's lifecycle
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // State variable for the color
    private var currentPhotoColor by mutableStateOf(ComposeColor.Gray)

    // Uri holders for images
    private var originalImageUri by mutableStateOf<Uri?>(null)
    private var colorizedImageUri by mutableStateOf<Uri?>(null)

    // Launcher for capturing images
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            originalImageUri?.let { uri ->
                // Load the bitmap from the Uri
                val bitmap = loadBitmapFromUri(this, uri)
                bitmap?.let {
                    // Map z-axis to color
                    val color = mapZAxisToColor(gravityData.getOrElse(2) { 0f })

                    // Colorize the bitmap
                    val colorizedBitmap = colorizeBitmap(it, color.toColor())

                    // Save the colorized bitmap to a new Uri
                    colorizedImageUri = saveBitmapToUri(this, colorizedBitmap)

                    // Update the UI with the colorized image
                    activityScope.launch {
                        _sensorDataFlow.emit("Color Code" to String.format("#%06X", 0xFFFFFF and color))
                    }
                }
            }
        } else {
            Log.e("MainActivity", "Image capture failed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Setup permission launchers
        val cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                // Permission granted, proceed with camera-related tasks
                launchCamera()
            } else {
                // Handle permission denied
                Log.e("MainActivity", "Camera permission denied")
                // Optionally, inform the user with a Toast or Snackbar
            }
        }

        val locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            if (fineLocationGranted || coarseLocationGranted) {
                // Permission granted, proceed with location-related tasks
                startLocationTracking()
            } else {
                // Handle permission denied
                Log.e("MainActivity", "Location permissions denied")
                // Optionally, inform the user with a Toast or Snackbar
            }
        }

        // Check and request permissions as needed
        checkAndRequestPermissions(
            cameraPermissionLauncher,
            locationPermissionLauncher
        )

        setContent {
            MagnetophotoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(currentPhotoColor),
                        color = ComposeColor.Transparent // Background is handled by currentPhotoColor
                    ) {
                        MagnetoColorizerApp(
                            colorizedImageUri = colorizedImageUri,
                            onTakePhotoClick = { launchCamera() } // Directly call launchCamera()
                        )
                    }
                }
            }
        }

        // Initialize sensor data map with default values
        activityScope.launch {
            _sensorDataFlow.emit("Info" to "Waiting for sensor data...")
        }
    }

    override fun onResume() {
        super.onResume()
        registerAndFlushSensors()
    }

    override fun onPause() {
        super.onPause()
        unregisterSensors()
    }

    private fun registerAndFlushSensors() {
        listOf(
            gravitySensor,
            magnetometerSensor,
            linearAccelSensor,
            gyroscopeSensor,
            rotationVectorSensor,
            proximitySensor,
            ambientLightSensor
        ).filterNotNull().forEach { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.flush(this) // Correct: Passing SensorEventListener
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_GRAVITY -> gravityData = it.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> magnetometerData = it.values.clone()
                Sensor.TYPE_LINEAR_ACCELERATION -> linearAccelData = it.values.clone()
                Sensor.TYPE_GYROSCOPE -> gyroscopeData = it.values.clone()
                Sensor.TYPE_ROTATION_VECTOR -> rotationVectorData = it.values.clone()
                Sensor.TYPE_PROXIMITY -> proximityData = it.values[0]
                Sensor.TYPE_LIGHT -> ambientLightData = it.values[0]
            }

            // Perform sensor fusion when all data is available
            if (isAllDataAvailable()) {
                fuseSensorData()
            }
        }
    }

    private fun isAllDataAvailable(): Boolean {
        // Basic check to ensure data arrays are initialized
        return gravityData.isNotEmpty() &&
                magnetometerData.isNotEmpty() &&
                linearAccelData.isNotEmpty() &&
                gyroscopeData.isNotEmpty() &&
                rotationVectorData.isNotEmpty()
    }

    private fun fuseSensorData() {
        // Multiply corresponding values
        val fusedX = gravityData[0] * magnetometerData[0] * linearAccelData[0] * gyroscopeData[0] * rotationVectorData[0]
        val fusedY = gravityData[1] * magnetometerData[1] * linearAccelData[1] * gyroscopeData[1] * rotationVectorData[1]
        val fusedZ = gravityData[2] * magnetometerData[2] * linearAccelData[2] * gyroscopeData[2] * rotationVectorData[2]

        // Normalize the fused values to 0-255 for RGB
        val normalizedX = normalizeValue(fusedX)
        val normalizedY = normalizeValue(fusedY)
        val normalizedZ = normalizeValue(fusedZ)

        // Create the RGB color
        val color = android.graphics.Color.rgb(normalizedX, normalizedY, normalizedZ)

        // Update the state variable for background color
        currentPhotoColor = ComposeColor(color)
    }

    private fun normalizeValue(value: Float): Int {
        // Simple normalization logic. Adjust based on expected sensor value ranges.
        // Prevent division by zero and handle extreme values
        return try {
            val minValue = -1000f // Example min value, adjust as needed
            val maxValue = 1000f  // Example max value, adjust as needed
            val clamped = value.coerceIn(minValue, maxValue)
            ((clamped - minValue) / (maxValue - minValue) * 255).toInt().coerceIn(0, 255)
        } catch (e: Exception) {
            Log.e("MainActivity", "Normalization error", e)
            0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Optional: Handle accuracy changes if needed
    }

    override fun onFlushCompleted(sensor: Sensor?) {
        sensor?.let {
            activityScope.launch {
                _sensorDataFlow.emit("${it.name} Flushed" to "Flush Completed")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel() // Cancel all coroutines when activity is destroyed
    }

    // Permission Handling Functions

    private fun checkAndRequestPermissions(
        cameraPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
        locationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
    ) {
        val cameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        )
        val fineLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        when {
            cameraPermission != PackageManager.PERMISSION_GRANTED -> {
                // Request CAMERA permission
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            fineLocationPermission != PackageManager.PERMISSION_GRANTED ||
                    coarseLocationPermission != PackageManager.PERMISSION_GRANTED -> {
                // Request LOCATION permissions
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            else -> {
                // Permissions already granted, proceed with necessary tasks
                startCamera()
                startLocationTracking()
            }
        }
    }

    private fun checkCameraPermission(
        cameraPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    ) {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) -> {
                // Permission already granted, launch camera
                launchCamera()
            }
            else -> {
                // Request CAMERA permission
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        // Implement your camera launch logic here if needed
        // For example, you might set up CameraX or prepare for image capture
    }

    private fun startLocationTracking() {
        // Implement your location tracking logic here if needed
        // For example, using FusedLocationProviderClient
    }

    // Function to launch camera
    private fun launchCamera() {
        activityScope.launch(Dispatchers.IO) {
            originalImageUri = createImageUri(this@MainActivity)
            originalImageUri?.let { uri ->
                withContext(Dispatchers.Main) {
                    cameraLauncher.launch(uri)
                }
            }
        }
    }

    // Function to create image Uri
    private fun createImageUri(context: Context): Uri? {
        return try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MagnetoPhoto")
                }
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to create image Uri", e)
            null
        }
    }

    // Function to load bitmap from Uri
    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: IOException) {
            Log.e("MainActivity", "Failed to load bitmap from Uri: $uri", e)
            null
        }
    }

    // Function to map z-axis to color
    private fun mapZAxisToColor(zValue: Float): Int {
        // Example mapping: blue to red based on zValue
        // Adjust the mapping logic as per your requirements
        val normalizedZ = ((zValue + 100f) / 200f).coerceIn(0f, 1f) // Normalize to [0,1]
        val red = (normalizedZ * 255).toInt()
        val blue = ((1f - normalizedZ) * 255).toInt()
        return android.graphics.Color.rgb(red, 0, blue)
    }

    // Function to colorize the bitmap
    private fun colorizeBitmap(originalBitmap: Bitmap, color: android.graphics.Color): Bitmap {
        val bitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        val colorFilter = android.graphics.PorterDuffColorFilter(color.toArgb(), android.graphics.PorterDuff.Mode.SRC_ATOP)
        paint.colorFilter = colorFilter
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return bitmap
    }

    // Function to save bitmap to Uri
    private fun saveBitmapToUri(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "COLORIZED_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MagnetoPhoto/Colorized")
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                }
                it
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to save colorized bitmap", e)
            null
        }
    }
}

@Composable
fun MagnetoColorizerApp(
    colorizedImageUri: Uri?,
    onTakePhotoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = onTakePhotoClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Take Photo")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (colorizedImageUri != null) {
            Image(
                bitmap = loadBitmapFromUri(context, colorizedImageUri)?.asImageBitmap() ?: return@Column,
                contentDescription = "Colorized Captured Image",
                modifier = Modifier
                    .size(300.dp)
                    .padding(8.dp)
            )
        } else {
            Text("No image captured yet.")
        }
    }
}

// Helper function to load bitmap from Uri
fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        BitmapFactory.decodeStream(inputStream)
    } catch (e: IOException) {
        Log.e("MagnetoColorizerApp", "Failed to load bitmap from Uri: $uri", e)
        null
    }
}