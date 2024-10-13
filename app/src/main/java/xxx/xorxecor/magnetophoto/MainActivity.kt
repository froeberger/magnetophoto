package xxx.xorxecor.magnetophoto // Replace with your actual package name

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
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
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.graphics.Color as ComposeColor

class MainActivity : ComponentActivity(), SensorEventListener2 {

    private lateinit var sensorManager: SensorManager

    // Define sensors
    private val gravitySensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) }
    private val magnetometerSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) }
    private val linearAccelSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) }
    private val gyroscopeSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    private val rotationVectorSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }

    // Sensor data holders (only z-axis)
    private var gravityZ = 0f
    private var magnetometerZ = 0f
    private var linearAccelZ = 0f
    private var gyroscopeZ = 0f
    private var rotationVectorZ = 0f

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
                // Load the bitmap from the Uri on a background thread
                activityScope.launch(Dispatchers.IO) {
                    val bitmap = loadBitmapFromUri(this@MainActivity, uri)
                    bitmap?.let {
                        // Map z-axis to color
                        val color = mapZAxisToColor()

                        // Colorize the bitmap on a background thread
                        val colorizedBitmap = colorizeBitmap(it, color)

                        // Save the colorized bitmap to a new Uri on a background thread
                        val savedUri = saveBitmapToUri(this@MainActivity, colorizedBitmap)

                        withContext(Dispatchers.Main) {
                            if (savedUri != null) {
                                colorizedImageUri = savedUri
                                Toast.makeText(this@MainActivity, "Image colorized and saved.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to save colorized image.", Toast.LENGTH_SHORT).show()
                            }
                        }

                        // Update the UI with the color code
                        activityScope.launch {
                            _sensorDataFlow.emit("Color Code" to String.format("#%06X", 0xFFFFFF and color))
                        }
                    }
                }
            }
        } else {
            Log.e("MainActivity", "Image capture failed")
            // Inform the user
            activityScope.launch(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Image capture failed.", Toast.LENGTH_SHORT).show()
            }
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
                // Inform the user
                Toast.makeText(this, "Camera permission is required to take photos.", Toast.LENGTH_LONG).show()
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
                // Inform the user
                Toast.makeText(this, "Location permissions are required for certain features.", Toast.LENGTH_LONG).show()
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
            rotationVectorSensor
        ).filterNotNull().forEach { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.flush(this) // Corrected: Passing SensorEventListener instead of Sensor
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_GRAVITY -> gravityZ = it.values.getOrElse(2) { 0f }
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> magnetometerZ = it.values.getOrElse(2) { 0f }
                Sensor.TYPE_LINEAR_ACCELERATION -> linearAccelZ = it.values.getOrElse(2) { 0f }
                Sensor.TYPE_GYROSCOPE -> gyroscopeZ = it.values.getOrElse(2) { 0f }
                Sensor.TYPE_ROTATION_VECTOR -> rotationVectorZ = it.values.getOrElse(2) { 0f }
            }

            // Perform sensor fusion when all data is available
            if (isAllDataAvailable()) {
                fuseSensorData()
            }
        }
    }

    private fun isAllDataAvailable(): Boolean {
        // Basic check to ensure all z-values are initialized
        return gravityZ != 0f &&
                magnetometerZ != 0f &&
                linearAccelZ != 0f &&
                gyroscopeZ != 0f &&
                rotationVectorZ != 0f
    }

    private fun fuseSensorData() {
        // Define sensor pairings for RGB
        val r = gravityZ * magnetometerZ
        val g = linearAccelZ * gyroscopeZ
        val b = rotationVectorZ * gravityZ // Using gravityZ again for pairing

        // Normalize each component to 0-255
        val normalizedR = normalizeValue(r)
        val normalizedG = normalizeValue(g)
        val normalizedB = normalizeValue(b)

        // Create the RGB color with full opacity
        val color = AndroidColor.rgb(normalizedR, normalizedG, normalizedB)

        // Update the state variable for background color
        currentPhotoColor = ComposeColor(color)
    }

    private fun normalizeValue(value: Float): Int {
        // Simple normalization logic based on expected sensor value ranges.
        // Adjust minValue and maxValue as per your sensor's specifications.
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

    // Function to load bitmap from Uri with proper resource management
    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Failed to load bitmap from Uri: $uri", e)
            null
        }
    }

    // Function to map z-axis to color based on sensor pairings
    private fun mapZAxisToColor(): Int {
        // Pairings:
        // R = GravityZ * MagnetometerZ
        // G = LinearAccelZ * GyroscopeZ
        // B = RotationVectorZ * GravityZ

        val r = gravityZ * magnetometerZ
        val g = linearAccelZ * gyroscopeZ
        val b = rotationVectorZ * gravityZ

        // Normalize each component to 0-255
        val normalizedR = normalizeValue(r)
        val normalizedG = normalizeValue(g)
        val normalizedB = normalizeValue(b)

        // Create the RGB color with full opacity
        return AndroidColor.rgb(normalizedR, normalizedG, normalizedB)
    }

    // Function to colorize the bitmap with a semi-transparent color overlay
    private fun colorizeBitmap(originalBitmap: Bitmap, color: Int): Bitmap {
        // Create a mutable copy of the original bitmap
        val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = AndroidPaint()

        // Set the color with desired alpha for transparency
        paint.color = color
        paint.alpha = 100f.toInt() // Adjust alpha as needed (0-255)

        // Draw a semi-transparent rectangle over the entire image
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)

        return mutableBitmap
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
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        }
    } catch (e: IOException) {
        Log.e("MagnetoColorizerApp", "Failed to load bitmap from Uri: $uri", e)
        null
    }
}