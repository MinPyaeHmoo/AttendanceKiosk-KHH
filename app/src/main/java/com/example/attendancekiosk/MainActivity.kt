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
import android.view.WindowManager
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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.attendancekiosk.data.AttendanceDatabase
import com.example.attendancekiosk.data.AttendanceRecord
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private var statusText by mutableStateOf("QR ကုဒ် ပြပါ")
    private var showButtons by mutableStateOf(false)
    private var pendingEmployeeId: String? = null
    private var successMessage by mutableStateOf<String?>(null)
    private var showLunchSurvey by mutableStateOf(false)
    private var lunchSurveyEmployeeId: String? = null
    private var announcements by mutableStateOf<List<String>>(emptyList())
    private var lunchSuccessMessage by mutableStateOf<String?>(null)

    private val timeoutHandler  = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var announcementListener: ListenerRegistration? = null
    private var scanTimeoutMs    = 10_000L

    private var isStandby by mutableStateOf(false)
    private val standbyHandler  = Handler(Looper.getMainLooper())
    private val standbyRunnable = Runnable { enterStandby() }
    private var standbyTimeoutMs = 30_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        viewFinder = PreviewView(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        fetchRemoteConfig()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        setupAnnouncementListener()
        resetStandbyTimer()

        setContent {
            KioskScreen(
                cameraPreviewView = viewFinder,
                statusText = statusText,
                showButtons = showButtons,
                successMessage = successMessage,
                lunchSuccessMessage = lunchSuccessMessage,
                showLunchSurvey = showLunchSurvey,
                announcements = announcements,
                isStandby = isStandby,
                onClockIn = { processAction("CLOCK_IN") },
                onClockOut = { processAction("CLOCK_OUT") },
                onLunchYes = { saveLunchResponse(true) },
                onLunchNo = { saveLunchResponse(false) },
                onWakeUp = { exitStandby(); resetStandbyTimer() }
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                statusText = "ကင်မရာ ခွင့်ပြုချက် လိုအပ်သည်"
                Toast.makeText(this, "ကင်မရာ ခွင့်မပြုပါ။ ဆက်တင်တွင် ခွင့်ပြုပါ။", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        announcementListener?.remove()
        cameraExecutor.shutdown()
        standbyHandler.removeCallbacks(standbyRunnable)
        timeoutHandler.removeCallbacksAndMessages(null)
    }

    private fun fetchRemoteConfig() {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        remoteConfig.setDefaultsAsync(mapOf(
            "scan_timeout_ms"    to 10_000L,
            "standby_timeout_ms" to 30_000L
        ))
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                scanTimeoutMs    = remoteConfig.getLong("scan_timeout_ms").coerceAtLeast(3_000L)
                standbyTimeoutMs = remoteConfig.getLong("standby_timeout_ms").coerceAtLeast(10_000L)
                Log.d(TAG, "Remote config — scan: ${scanTimeoutMs}ms  standby: ${standbyTimeoutMs}ms")
            }
        }
    }

    private fun enterStandby() {
        isStandby = true
        setScreenBrightness(0.08f)
        if (::qrAnalyzer.isInitialized) qrAnalyzer.setStandbyMode(true)
        Log.d(TAG, "Standby entered")
    }

    private fun exitStandby() {
        if (!isStandby) return
        isStandby = false
        setScreenBrightness(-1f)   // restore system brightness
        if (::qrAnalyzer.isInitialized) qrAnalyzer.setStandbyMode(false)
        Log.d(TAG, "Standby exited")
    }

    private fun resetStandbyTimer() {
        standbyHandler.removeCallbacks(standbyRunnable)
        standbyHandler.postDelayed(standbyRunnable, standbyTimeoutMs)
    }

    private fun setScreenBrightness(brightness: Float) {
        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
    }

    private fun setupAnnouncementListener() {
        announcementListener = FirebaseFirestore.getInstance()
            .collection("announcements")
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                announcements = snapshot.documents.mapNotNull { it.getString("message") }
            }
    }

    private fun processAction(actionType: String) {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }

        pendingEmployeeId?.let { id ->
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AttendanceDatabase.getDatabase(applicationContext)

                val lastRecord = db.attendanceDao().getLatestRecordForEmployee(id)
                val lastAction = lastRecord?.recordType

                // TODO: uncomment after testing — prevents clocking in twice without clocking out
                // if (actionType == "CLOCK_IN" && lastAction == "CLOCK_IN") {
                //     runOnUiThread { resetKiosk("အမှား: ဦးဆုံး ထွက်ချိန်မှတ်တမ်းတင်ပြီးမှ ပြန်ဝင်ပါ!") }
                // } else
                if (actionType == "CLOCK_OUT" && (lastAction == "CLOCK_OUT" || lastAction == null)) {
                    runOnUiThread { resetKiosk("အမှား: ဦးစွာ ဝင်ချိန်မှတ်တမ်းတင်ရမည်!") }
                } else {
                    runOnUiThread {
                        showButtons = false
                        statusText = "မှတ်တမ်းတင်နေသည်..."
                        takePhotoWithWatermark(id, actionType)
                    }
                }
            }
        }
    }

    private fun saveLunchResponse(hasLunch: Boolean) {
        val id = lunchSurveyEmployeeId ?: run { resetKiosk(); return }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val data = hashMapOf(
            "employeeId" to id,
            "date" to today,
            "hasLunch" to hasLunch,
            "timestamp" to Timestamp.now()
        )
        FirebaseFirestore.getInstance()
            .collection("lunch_surveys")
            .add(data)
            .addOnSuccessListener { Log.d(TAG, "Lunch survey saved for $id") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to save lunch survey", e) }

        showLunchSurvey = false
        lunchSurveyEmployeeId = null

        lunchSuccessMessage = if (hasLunch) "နေ့လည်စာ မှတ်တမ်းတင်ပြီး 🍱" else "မှတ်တမ်းတင်ပြီး 👍"
        timeoutHandler.postDelayed({
            lunchSuccessMessage = null
            resetKiosk()
        }, 2500)
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
                        exitStandby()
                        resetStandbyTimer()
                        pendingEmployeeId = scannedData
                        statusText = "ID: $scannedData\nလုပ်ဆောင်ချက် ရွေးချယ်ပါ"
                        showButtons = true

                        timeoutRunnable = Runnable { if (showButtons) resetKiosk("QR ကုဒ် ဖတ်ရမည့် အချိန် ကုန်သွားပါပြီ") }
                        timeoutHandler.postDelayed(timeoutRunnable!!, scanTimeoutMs)
                    }
                },
                onNoFaceDetected = {
                    runOnUiThread { statusText = "ကျေးဇူးပြု၍ ကင်မရာကို ကြည့်ပါ!" }
                }
            )

            imageAnalysis.setAnalyzer(cameraExecutor, qrAnalyzer)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Camera connection failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoWithWatermark(employeeId: String, recordType: String) {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(image: ImageProxy) {
                    val originalBitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()

                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(
                        originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
                    )
                    // createBitmap may return the original (immutable) bitmap when the matrix is
                    // identity; always make an explicit mutable copy so Canvas can draw on it.
                    val mutableBitmap = rotated.copy(rotated.config ?: Bitmap.Config.ARGB_8888, true)
                    if (mutableBitmap !== rotated) rotated.recycle()

                    val canvas = Canvas(mutableBitmap)
                    val paint = Paint().apply {
                        color = AndroidColor.YELLOW
                        textSize = mutableBitmap.height * 0.035f
                        typeface = Typeface.DEFAULT_BOLD
                        setShadowLayer(5f, 2f, 2f, AndroidColor.BLACK)
                        isAntiAlias = true
                    }

                    val currentTimeMs = System.currentTimeMillis()
                    val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(currentTimeMs))
                    val watermarkText = "ID: $employeeId | $recordType | $timeText"
                    canvas.drawText(watermarkText, 50f, mutableBitmap.height - 80f, paint)

                    lifecycleScope.launch(Dispatchers.IO) {
                        val savedUri = saveBitmapToGallery(mutableBitmap, employeeId, currentTimeMs)

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

                            val friendlyAction = if (recordType == "CLOCK_IN") "ဝင်ချိန်မှတ်တမ်းတင်ပြီး!" else "ထွက်ချိန်မှတ်တမ်းတင်ပြီး!"
                            runOnUiThread {
                                showSuccessAndReset(friendlyAction, recordType, employeeId)
                            }
                        } else {
                            runOnUiThread { resetKiosk("ဓာတ်ပုံသိမ်းဆည်းရာတွင် အမှားဖြစ်သည်") }
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    runOnUiThread { resetKiosk("ကင်မရာ အမှားဖြစ်သည်။ ဓာတ်ပုံမရပါ") }
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

    private fun showSuccessAndReset(message: String, recordType: String, employeeId: String) {
        successMessage = message
        timeoutHandler.postDelayed({
            successMessage = null
            if (recordType == "CLOCK_IN") {
                lunchSurveyEmployeeId = employeeId
                showLunchSurvey = true
            } else {
                resetKiosk()
            }
        }, 2000)
    }

    private fun resetKiosk(toastMessage: String? = null) {
        toastMessage?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        showButtons = false
        showLunchSurvey = false
        statusText = "QR ကုဒ် ပြပါ"
        pendingEmployeeId = null
        lunchSurveyEmployeeId = null
        if (::qrAnalyzer.isInitialized) qrAnalyzer.resumeScanning()
        resetStandbyTimer()
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
    lunchSuccessMessage: String?,
    showLunchSurvey: Boolean,
    announcements: List<String>,
    isStandby: Boolean,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    onLunchYes: () -> Unit,
    onLunchNo: () -> Unit,
    onWakeUp: () -> Unit
) {
    val hasAnnouncements = announcements.isNotEmpty()

    var currentAnnouncementIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(announcements) {
        currentAnnouncementIndex = 0
        if (announcements.size > 1) {
            while (true) {
                delay(5000)
                currentAnnouncementIndex = (currentAnnouncementIndex + 1) % announcements.size
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val isTablet    = maxWidth  >= 600.dp
        val isLandscape = maxWidth  > maxHeight

        // Responsive sizing — scales up for tablets / landscape 16:9 screens
        val clockTextSize    = if (isTablet) 80f   else 60f
        val dateTextSize     = if (isTablet) 26f   else 20f
        val announceFontSz   = if (isTablet) 30.sp else 24.sp
        val statusFontSz     = if (isTablet) 24.sp else 20.sp
        val scannerSize      = if (isTablet) 380.dp else 280.dp
        val scannerOffsetY   = if (isLandscape) 0.dp else 28.dp
        val btnWidth         = if (isTablet) 200.dp else 155.dp
        val btnHeight        = if (isTablet) 90.dp  else 72.dp
        val btnFontSz        = if (isTablet) 26.sp  else 20.sp
        val successIconSz    = if (isTablet) 160.dp else 120.dp
        val successFontSz    = if (isTablet) 40.sp  else 32.sp
        val lunchBtnWidth    = if (isTablet) 190.dp else 148.dp
        val lunchEmojiSz     = if (isTablet) 90.sp  else 64.sp
        val lunchQnFontSz    = if (isTablet) 34.sp  else 28.sp
        val lunchBtnFontSz   = if (isTablet) 26.sp  else 22.sp

        // Camera preview — full screen
        AndroidView(factory = { cameraPreviewView }, modifier = Modifier.fillMaxSize())

        // Top gradient vignette for readability
        Box(
            modifier = Modifier
                .fillMaxWidth().height(300.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xD9000000), Color.Transparent)))
        )

        // Bottom gradient vignette
        Box(
            modifier = Modifier
                .fillMaxWidth().height(260.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
        )

        // ── Announcement bar — TOP ──────────────────────────────────
        if (hasAnnouncements) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF0C2340), Color(0xFF0EA5E9), Color(0xFF0C2340))
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text("📢", fontSize = 24.sp, modifier = Modifier.padding(end = 10.dp))
                Text(
                    text = announcements[currentAnnouncementIndex.coerceIn(0, announcements.size - 1)],
                    color = Color.White,
                    fontSize = announceFontSz,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (announcements.size > 1) {
                    Text(
                        text = "${currentAnnouncementIndex + 1}/${announcements.size}",
                        color = Color(0xAAFFFFFF),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }

        // ── Clock + Branding ────────────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (hasAnnouncements) 82.dp else 48.dp)
        ) {
            Text(
                text = "KHH ATTENDANCE",
                color = Color(0xFF38BDF8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            AndroidView(factory = { ctx ->
                TextClock(ctx).apply {
                    format12Hour = "hh:mm a"
                    textSize = clockTextSize
                    setTextColor(AndroidColor.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(8f, 0f, 3f, AndroidColor.parseColor("#66000000"))
                }
            })
            AndroidView(factory = { ctx ->
                TextClock(ctx).apply {
                    format12Hour = "EEEE, MMMM dd"
                    textSize = dateTextSize
                    setTextColor(AndroidColor.parseColor("#94A3B8"))
                    setShadowLayer(4f, 0f, 1f, AndroidColor.BLACK)
                }
            })
        }

        // ── Scanner frame with corner markers ───────────────────────
        ScannerFrame(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = scannerOffsetY),
            frameSize = scannerSize
        )

        // ── Status chip ─────────────────────────────────────────────
        if (!showButtons && successMessage == null && lunchSuccessMessage == null && !showLunchSurvey) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(50.dp))
                    .padding(horizontal = 36.dp, vertical = 14.dp)
            ) {
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = statusFontSz,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // ── Action buttons ──────────────────────────────────────────
        if (showButtons && successMessage == null && !showLunchSurvey) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .background(Color(0xD9000000), RoundedCornerShape(28.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp)
            ) {
                Text(
                    text = statusText,
                    color = Color(0xFF94A3B8),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = onClockIn,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(width = btnWidth, height = btnHeight)
                    ) {
                        Text("ဝင်ချိန်", fontSize = btnFontSz, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onClockOut,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFDC2626),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(width = btnWidth, height = btnHeight)
                    ) {
                        Text("ထွက်ချိန်", fontSize = btnFontSz, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Clock-in/out success overlay ────────────────────────────
        if (successMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xE0000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(32.dp))
                        .padding(horizontal = 56.dp, vertical = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF22C55E),
                        modifier = Modifier.size(successIconSz)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = successMessage,
                        color = Color.White,
                        fontSize = successFontSz,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ── Lunch thank-you overlay ─────────────────────────────────
        if (lunchSuccessMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xE0000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(32.dp))
                        .padding(horizontal = 56.dp, vertical = 48.dp)
                ) {
                    Text("✅", fontSize = 80.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = lunchSuccessMessage,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ── Standby screen-saver ────────────────────────────────────
        if (isStandby) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF080D18))
                    .clickable { onWakeUp() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "KHH ATTENDANCE",
                        color = Color(0xFF1D4ED8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AndroidView(factory = { ctx ->
                        TextClock(ctx).apply {
                            format12Hour = "hh:mm a"
                            textSize = clockTextSize
                            setTextColor(AndroidColor.parseColor("#3B82F6"))
                            typeface = Typeface.DEFAULT_BOLD
                        }
                    })
                    AndroidView(factory = { ctx ->
                        TextClock(ctx).apply {
                            format12Hour = "EEEE, MMMM dd"
                            textSize = dateTextSize
                            setTextColor(AndroidColor.parseColor("#1E3A5F"))
                        }
                    })
                    Spacer(modifier = Modifier.height(48.dp))
                    Text(
                        text = "QR ကုဒ် ပြ၍ ဆက်လက်ဆောင်ရွက်ပါ",
                        color = Color(0xFF1E3A5F),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── Lunch survey overlay ─────────────────────────────────────
        if (showLunchSurvey) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xE0000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(32.dp))
                        .padding(horizontal = 48.dp, vertical = 44.dp)
                ) {
                    Text("🍱", fontSize = lunchEmojiSz)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "ယနေ့ နေ့လည်စာ စားမှာလား?",
                        color = Color.White,
                        fontSize = lunchQnFontSz,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = onLunchYes,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF16A34A),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.size(width = lunchBtnWidth, height = btnHeight)
                        ) {
                            Text("ဟုတ်ကဲ့", fontSize = lunchBtnFontSz, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onLunchNo,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFDC2626),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.size(width = lunchBtnWidth, height = btnHeight)
                        ) {
                            Text("မစားပါ", fontSize = lunchBtnFontSz, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScannerFrame(modifier: Modifier = Modifier, frameSize: Dp = 280.dp) {
    val cornerLen = (frameSize.value * 0.13f).dp
    val stroke    = 4.dp

    Box(modifier = modifier.size(frameSize)) {
        // Subtle tint inside frame
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x1038BDF8), RoundedCornerShape(16.dp))
        )
        // Top-left
        Box(Modifier.align(Alignment.TopStart).width(cornerLen).height(stroke).background(Color.White))
        Box(Modifier.align(Alignment.TopStart).width(stroke).height(cornerLen).background(Color.White))
        // Top-right
        Box(Modifier.align(Alignment.TopEnd).width(cornerLen).height(stroke).background(Color.White))
        Box(Modifier.align(Alignment.TopEnd).width(stroke).height(cornerLen).background(Color.White))
        // Bottom-left
        Box(Modifier.align(Alignment.BottomStart).width(cornerLen).height(stroke).background(Color.White))
        Box(Modifier.align(Alignment.BottomStart).width(stroke).height(cornerLen).background(Color.White))
        // Bottom-right
        Box(Modifier.align(Alignment.BottomEnd).width(cornerLen).height(stroke).background(Color.White))
        Box(Modifier.align(Alignment.BottomEnd).width(stroke).height(cornerLen).background(Color.White))
    }
}
