package com.example.attendancekiosk

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.TextClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.attendancekiosk.data.AttendanceDatabase
import com.example.attendancekiosk.data.AttendanceRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private lateinit var qrAnalyzer: QrCodeAnalyzer
    private lateinit var viewFinder: PreviewView

    // UI State Variables
    private var statusText by mutableStateOf("Ready for next employee")
    private var showButtons by mutableStateOf(false)
    private var pendingEmployeeId: String? = null
    private var successMessage by mutableStateOf<String?>(null) // NEW: For the Success UI

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        viewFinder = PreviewView(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        setContent {
            KioskScreen(
                cameraPreviewView = viewFinder,
                statusText = statusText,
                showButtons = showButtons,
                successMessage = successMessage,
                onClockIn = { processAction("CLOCK_IN") },
                onClockOut = { processAction("CLOCK_OUT") }
            )
        }
    }

    private fun processAction(actionType: String) {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }

        pendingEmployeeId?.let { id ->
            CoroutineScope(Dispatchers.IO).launch {
                val db = AttendanceDatabase.getDatabase(applicationContext)
                val lastRecord = db.attendanceDao().getLatestRecordForEmployee(id)
                val lastAction = lastRecord?.recordType

                if (actionType == "CLOCK_IN" && lastAction == "CLOCK_IN") {
                    runOnUiThread { resetKiosk("Error: You are already Clocked In!") }
                } else if (actionType == "CLOCK_OUT" && (lastAction == "CLOCK_OUT" || lastAction == null)) {
                    runOnUiThread { resetKiosk("Error: You must Clock In first!") }
                } else {
                    runOnUiThread {
                        showButtons = false
                        statusText = "Logging $actionType..."
                        takePhotoWithWatermark(id, actionType)
                    }
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            qrAnalyzer = QrCodeAnalyzer(
                onValidScan = { scannedData ->
                    runOnUiThread {
                        pendingEmployeeId = scannedData
                        statusText = "ID: $scannedData\nPlease select action"
                        showButtons = true

                        timeoutRunnable = Runnable { if (showButtons) resetKiosk("Scan timed out") }
                        timeoutHandler.postDelayed(timeoutRunnable!!, 10000)
                    }
                },
                onNoFaceDetected = {
                    runOnUiThread { statusText = "Please look at the camera!" }
                }
            )

            imageAnalysis.setAnalyzer(cameraExecutor, qrAnalyzer)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture, imageAnalysis
                )
            } catch(exc: Exception) {
                Log.e(TAG, "Camera connection failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // NEW: Takes photo in memory, draws watermark, saves to gallery
    private fun takePhotoWithWatermark(employeeId: String, recordType: String) {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(image: ImageProxy) {
                    // 1. Convert ImageProxy to Bitmap and fix rotation
                    val originalBitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()

                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotatedBitmap = Bitmap.createBitmap(
                        originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
                    )

                    // 2. Draw the Watermark on the Bitmap
                    val canvas = Canvas(rotatedBitmap)
                    val paint = Paint().apply {
                        color = AndroidColor.YELLOW
                        // Dynamically size text based on camera resolution
                        textSize = rotatedBitmap.height * 0.035f
                        typeface = Typeface.DEFAULT_BOLD
                        setShadowLayer(5f, 2f, 2f, AndroidColor.BLACK)
                        isAntiAlias = true
                    }

                    val currentTimeMs = System.currentTimeMillis()
                    val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(currentTimeMs))
                    val watermarkText = "ID: $employeeId | $recordType | $timeText"

                    // Draw at bottom-left corner
                    canvas.drawText(watermarkText, 50f, rotatedBitmap.height - 80f, paint)

                    // 3. Save modified Bitmap to Gallery and DB
                    CoroutineScope(Dispatchers.IO).launch {
                        val savedUri = saveBitmapToGallery(rotatedBitmap, employeeId, currentTimeMs)

                        if (savedUri != null) {
                            val record = AttendanceRecord(
                                employeeId = employeeId,
                                timestamp = currentTimeMs,
                                photoPath = savedUri.toString(),
                                recordType = recordType,
                                isSynced = false
                            )
                            AttendanceDatabase.getDatabase(applicationContext).attendanceDao().insertRecord(record)

                            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
                            WorkManager.getInstance(applicationContext).enqueue(syncRequest)

                            // Show Beautiful Success UI
                            val friendlyAction = if (recordType == "CLOCK_IN") "Clock In" else "Clock Out"
                            runOnUiThread { showSuccessAndReset("$friendlyAction Successful!") }
                        } else {
                            runOnUiThread { resetKiosk("Error saving photo to gallery.") }
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    runOnUiThread { resetKiosk("Camera Error: Could not save photo") }
                }
            }
        )
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, employeeId: String, timestamp: Long): Uri? {
        val fileName = "${employeeId}_${timestamp}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AttendanceKiosk")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { outStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
            }
        }
        return uri
    }

    private fun showSuccessAndReset(message: String) {
        successMessage = message
        // Keep the success screen up for 3 seconds, then reset automatically
        timeoutHandler.postDelayed({
            successMessage = null
            resetKiosk()
        }, 3000)
    }

    private fun resetKiosk(toastMessage: String? = null) {
        toastMessage?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        showButtons = false
        statusText = "Ready for next employee"
        pendingEmployeeId = null
        qrAnalyzer.resumeScanning()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "KHH_Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}

// ==========================================
// JETPACK COMPOSE UI
// ==========================================
@Composable
fun KioskScreen(
    cameraPreviewView: PreviewView,
    statusText: String,
    showButtons: Boolean,
    successMessage: String?,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(factory = { cameraPreviewView }, modifier = Modifier.fillMaxSize())

        // Clocks
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
        ) {
            AndroidView(factory = { ctx ->
                TextClock(ctx).apply {
                    format12Hour = "hh:mm a"
                    textSize = 64f
                    setTextColor(AndroidColor.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(4f, 2f, 2f, AndroidColor.BLACK)
                }
            })
            AndroidView(factory = { ctx ->
                TextClock(ctx).apply {
                    format12Hour = "EEEE, MMMM dd"
                    textSize = 24f
                    setTextColor(AndroidColor.LTGRAY)
                    setShadowLayer(4f, 2f, 2f, AndroidColor.BLACK)
                }
            })
        }

        // Target Box
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.Center)
                .offset(y = 40.dp)
                .border(4.dp, Color.White, RoundedCornerShape(24.dp))
        )

        // Status Text
        if (!showButtons && successMessage == null) {
            Text(
                text = statusText,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(32.dp))
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            )
        }

        // Action Buttons
        if (showButtons && successMessage == null) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(32.dp))
                    .padding(24.dp)
            ) {
                Button(
                    onClick = onClockIn,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    modifier = Modifier.size(width = 160.dp, height = 80.dp)
                ) {
                    Text("CLOCK IN", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = onClockOut,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                    modifier = Modifier.size(width = 160.dp, height = 80.dp)
                ) {
                    Text("CLOCK OUT", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // NEW: Giant Success Overlay UI
        if (successMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD9000000)), // Semi-transparent black background
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Success",
                        tint = Color(0xFF4CAF50), // Green Checkmark
                        modifier = Modifier.size(160.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = successMessage,
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}