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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val EXIT_PIN = "111111"   // ← change to your desired passcode

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private lateinit var qrAnalyzer: QrCodeAnalyzer
    private lateinit var viewFinder: PreviewView

    private var statusText          by mutableStateOf("QR ကုဒ် ပြပါ")
    private var showButtons         by mutableStateOf(false)
    private var pendingEmployeeId: String? = null
    private var pendingEmployeeName: String? = null
    private var successMessage      by mutableStateOf<String?>(null)
    private var showLunchSurvey     by mutableStateOf(false)
    private var lunchSurveyEmployeeId: String? = null
    private var announcements       by mutableStateOf<List<String>>(emptyList())
    private var lunchSuccessMessage by mutableStateOf<String?>(null)

    // Blocked overlay — shown when employee tries to clock in/out twice in one day
    private var blockedMessage      by mutableStateOf<String?>(null)

    private val timeoutHandler  = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var announcementListener: ListenerRegistration? = null
    private var scanTimeoutMs   = 10_000L

    private var isStandby        by mutableStateOf(false)
    private val standbyHandler   = Handler(Looper.getMainLooper())
    private val standbyRunnable  = Runnable { enterStandby() }
    private var standbyTimeoutMs = 30_000L

    // Acknowledgement overlay state
    private var showAckOverlay      by mutableStateOf(false)
    private var ackAnnouncementText by mutableStateOf<String?>(null)
    private var ackEmployeeId: String? = null
    private var ackRecordType: String? = null          // "CLOCK_IN" or "CLOCK_OUT"
    // Separate ACK announcements: ackType="CLOCK_IN" vs ackType="CLOCK_OUT" on Firestore doc
    private var activeClockInAck: Pair<String, String>?  = null  // docId → message for clock-in
    private var activeClockOutAck: Pair<String, String>? = null  // docId → message for clock-out

    // Exit passcode overlay
    private var showExitDialog    by mutableStateOf(false)
    private var exitPinInput      by mutableStateOf("")
    private var exitPinError      by mutableStateOf(false)
    private var brandingTapCount  = 0
    private var lastBrandingTapMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        startLockTask()

        viewFinder     = PreviewView(this)
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
                cameraPreviewView   = viewFinder,
                statusText          = statusText,
                showButtons         = showButtons,
                successMessage      = successMessage,
                lunchSuccessMessage = lunchSuccessMessage,
                showLunchSurvey     = showLunchSurvey,
                announcements       = announcements,
                isStandby           = isStandby,
                showAckOverlay      = showAckOverlay,
                ackAnnouncementText = ackAnnouncementText,
                ackRecordType       = ackRecordType,
                blockedMessage      = blockedMessage,
                onClockIn           = { processAction("CLOCK_IN") },
                onClockOut          = { processAction("CLOCK_OUT") },
                onLunchYes          = { saveLunchResponse(true) },
                onLunchNo           = { saveLunchResponse(false) },
                onWakeUp            = { exitStandby(); resetStandbyTimer() },
                onAcknowledge       = { saveAcknowledgement() },
                showExitDialog      = showExitDialog,
                exitPinInput        = exitPinInput,
                exitPinError        = exitPinError,
                onBrandingTap       = { onBrandingTap() },
                onExitPinKey        = { onExitPinKey(it) },
                onExitPinBackspace  = { onExitPinBackspace() },
                onExitPinDismiss    = { onExitPinDismiss() }
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Kiosk mode — back button disabled
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ── Exit passcode ──────────────────────────────────────────────────
    private fun onBrandingTap() {
        val now = System.currentTimeMillis()
        if (now - lastBrandingTapMs > 3000) brandingTapCount = 0
        lastBrandingTapMs = now
        if (++brandingTapCount >= 5) {
            brandingTapCount = 0
            showExitDialog = true
            exitPinInput   = ""
            exitPinError   = false
        }
    }

    private fun onExitPinKey(digit: String) {
        if (exitPinError || exitPinInput.length >= EXIT_PIN.length) return
        exitPinInput += digit
        if (exitPinInput.length == EXIT_PIN.length) {
            if (exitPinInput == EXIT_PIN) {
                stopLockTask()
                finishAndRemoveTask()
            } else {
                exitPinError = true
                timeoutHandler.postDelayed({
                    exitPinInput = ""
                    exitPinError = false
                }, 800)
            }
        }
    }

    private fun onExitPinBackspace() {
        if (exitPinInput.isNotEmpty()) {
            exitPinInput = exitPinInput.dropLast(1)
            exitPinError = false
        }
    }

    private fun onExitPinDismiss() {
        showExitDialog = false
        exitPinInput   = ""
        exitPinError   = false
    }

    // ── Remote config ──────────────────────────────────────────────────
    private fun fetchRemoteConfig() {
        val rc = FirebaseRemoteConfig.getInstance()
        rc.setDefaultsAsync(mapOf(
            "scan_timeout_ms"    to 10_000L,
            "standby_timeout_ms" to 30_000L
        ))
        rc.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                scanTimeoutMs    = rc.getLong("scan_timeout_ms").coerceAtLeast(3_000L)
                standbyTimeoutMs = rc.getLong("standby_timeout_ms").coerceAtLeast(10_000L)
                Log.d(TAG, "Remote config — scan: ${scanTimeoutMs}ms  standby: ${standbyTimeoutMs}ms")
            }
        }
    }

    // ── Standby ────────────────────────────────────────────────────────
    private fun enterStandby() {
        isStandby = true
        setScreenBrightness(0.08f)
        if (::qrAnalyzer.isInitialized) qrAnalyzer.setStandbyMode(true)
    }

    private fun exitStandby() {
        if (!isStandby) return
        isStandby = false
        setScreenBrightness(-1f)
        if (::qrAnalyzer.isInitialized) qrAnalyzer.setStandbyMode(false)
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

    // ── Announcement listener ──────────────────────────────────────────
    private fun setupAnnouncementListener() {
        announcementListener = FirebaseFirestore.getInstance()
            .collection("announcements")
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val regularList  = mutableListOf<String>()
                var clockInAck:  Pair<String, String>? = null
                var clockOutAck: Pair<String, String>? = null
                for (doc in snapshot.documents) {
                    val msg = doc.getString("message") ?: continue
                    if (doc.getBoolean("requiresAck") == true) {
                        // ackType field distinguishes clock-in vs clock-out announcements
                        val ackType = doc.getString("ackType") ?: "CLOCK_IN"
                        if (ackType == "CLOCK_OUT") {
                            if (clockOutAck == null) clockOutAck = Pair(doc.id, msg)
                        } else {
                            if (clockInAck == null) clockInAck = Pair(doc.id, msg)
                        }
                    } else {
                        regularList.add(msg)
                    }
                }
                announcements    = regularList
                activeClockInAck  = clockInAck
                activeClockOutAck = clockOutAck
            }
    }

    // ── Process action ─────────────────────────────────────────────────
    private fun processAction(actionType: String) {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }

        pendingEmployeeId?.let { id ->
            lifecycleScope.launch(Dispatchers.IO) {
                val dao = AttendanceDatabase.getDatabase(applicationContext).attendanceDao()

                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val startOfDay = cal.timeInMillis
                val endOfDay   = startOfDay + 86_400_000L

                val alreadyIn  = dao.getClockInForDay(id, startOfDay, endOfDay)
                val alreadyOut = dao.getClockOutForDay(id, startOfDay, endOfDay)

                when {
                    actionType == "CLOCK_IN" && alreadyIn != null ->
                        runOnUiThread { showBlockedOverlay("ဝင်ချိန် မှတ်တမ်းတင်ပြီးသား!\nယနေ့ ထပ်မတင်နိုင်ပါ") }

                    actionType == "CLOCK_OUT" && alreadyIn == null ->
                        runOnUiThread { showBlockedOverlay("ဦးစွာ ဝင်ချိန် မှတ်တမ်းတင်ပါ!") }

                    actionType == "CLOCK_OUT" && alreadyOut != null ->
                        runOnUiThread { showBlockedOverlay("ထွက်ချိန် မှတ်တမ်းတင်ပြီးသား!\nယနေ့ ထပ်မတင်နိုင်ပါ") }

                    else -> runOnUiThread {
                        showButtons = false
                        statusText  = "မှတ်တမ်းတင်နေသည်..."
                        takePhotoWithWatermark(id, actionType)
                    }
                }
            }
        }
    }

    // Shows the blocked overlay and auto-resets after 3 seconds
    private fun showBlockedOverlay(msg: String) {
        showButtons    = false
        blockedMessage = msg
        timeoutHandler.postDelayed({
            blockedMessage = null
            resetKiosk()
        }, 3000)
    }

    // ── Lunch survey ───────────────────────────────────────────────────
    private fun saveLunchResponse(hasLunch: Boolean) {
        val id = lunchSurveyEmployeeId ?: run { resetKiosk(); return }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        FirebaseFirestore.getInstance()
            .collection("lunch_surveys")
            .add(hashMapOf(
                "employeeId" to id,
                "date"       to today,
                "hasLunch"   to hasLunch,
                "timestamp"  to Timestamp.now()
            ))
            .addOnSuccessListener { Log.d(TAG, "Lunch survey saved for $id") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to save lunch survey", e) }

        postToSheets("LUNCH", id, System.currentTimeMillis(), hasLunch)

        showLunchSurvey        = false
        lunchSurveyEmployeeId  = null
        lunchSuccessMessage    = if (hasLunch) "နေ့လည်စာ မှတ်တမ်းတင်ပြီး 🍱" else "မှတ်တမ်းတင်ပြီး 👍"

        timeoutHandler.postDelayed({
            lunchSuccessMessage = null
            resetKiosk()
        }, 2500)
    }

    // ── Camera ─────────────────────────────────────────────────────────
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            qrAnalyzer = QrCodeAnalyzer(
                onValidScan = { scannedData ->
                    // QR holds "Name, Code" (e.g. "MinPyaeHmoo, MPH 001"); older badges may
                    // still be a bare code with no comma, in which case there's no name to greet with.
                    val parts = scannedData.split(",", limit = 2).map { it.trim() }
                    val (id, name) = if (parts.size == 2 && parts[1].isNotEmpty()) {
                        parts[1] to parts[0]
                    } else {
                        scannedData.trim() to null
                    }

                    runOnUiThread {
                        exitStandby()
                        resetStandbyTimer()
                        pendingEmployeeId   = id
                        pendingEmployeeName = name
                        statusText          = if (name != null)
                            "မင်္ဂလာပါ, $name\nလုပ်ဆောင်ချက် ရွေးချယ်ပါ"
                        else
                            "ID: $id\nလုပ်ဆောင်ချက် ရွေးချယ်ပါ"
                        showButtons         = true

                        timeoutRunnable = Runnable {
                            if (showButtons) resetKiosk("QR ကုဒ် ဖတ်ရမည့် အချိန် ကုန်သွားပါပြီ")
                        }
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
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview, imageCapture, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Camera connection failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Photo capture + watermark ──────────────────────────────────────
    private fun takePhotoWithWatermark(employeeId: String, recordType: String) {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val originalBitmap = image.toBitmap()
                    val rotation       = image.imageInfo.rotationDegrees
                    image.close()

                    val matrix  = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(
                        originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
                    )
                    val mutableBitmap = rotated.copy(rotated.config ?: Bitmap.Config.ARGB_8888, true)
                    if (mutableBitmap !== rotated) rotated.recycle()

                    val canvas = Canvas(mutableBitmap)
                    val paint  = Paint().apply {
                        color     = AndroidColor.YELLOW
                        textSize  = mutableBitmap.height * 0.035f
                        typeface  = Typeface.DEFAULT_BOLD
                        setShadowLayer(5f, 2f, 2f, AndroidColor.BLACK)
                        isAntiAlias = true
                    }

                    val currentTimeMs = System.currentTimeMillis()
                    val timeText      = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(currentTimeMs))
                    canvas.drawText("ID: $employeeId | $recordType | $timeText", 50f, mutableBitmap.height - 80f, paint)

                    lifecycleScope.launch(Dispatchers.IO) {
                        val savedUri = saveBitmapToGallery(mutableBitmap, employeeId, currentTimeMs)
                        if (savedUri != null) {
                            val record = AttendanceRecord(
                                employeeId  = employeeId,
                                timestamp   = currentTimeMs,
                                photoPath   = savedUri.toString(),
                                recordType  = recordType,
                                isSynced    = false
                            )
                            AttendanceDatabase.getDatabase(applicationContext)
                                .attendanceDao().insertRecord(record)
                            WorkManager.getInstance(applicationContext)
                                .enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())

                            val friendlyAction = if (recordType == "CLOCK_IN")
                                "ဝင်ချိန်မှတ်တမ်းတင်ပြီး!" else "ထွက်ချိန်မှတ်တမ်းတင်ပြီး!"
                            runOnUiThread {
                                showSuccessAndContinue(friendlyAction, recordType, employeeId, currentTimeMs)
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
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AttendanceKiosk")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }
        return uri
    }

    // ── Success → next step ────────────────────────────────────────────
    private fun showSuccessAndContinue(
        message: String,
        recordType: String,
        employeeId: String,
        timestampMs: Long
    ) {
        successMessage = message
        postToSheets(recordType, employeeId, timestampMs, name = pendingEmployeeName)

        timeoutHandler.postDelayed({
            successMessage = null
            // Pick the ACK announcement specific to this action type
            val ackAnn = if (recordType == "CLOCK_IN") activeClockInAck else activeClockOutAck
            when {
                ackAnn != null -> {
                    ackEmployeeId       = employeeId
                    ackAnnouncementText = ackAnn.second
                    ackRecordType       = recordType
                    showAckOverlay      = true
                }
                recordType == "CLOCK_IN" -> {
                    lunchSurveyEmployeeId = employeeId
                    showLunchSurvey       = true
                }
                else -> resetKiosk()
            }
        }, 2000)
    }

    // ── Save acknowledgement ───────────────────────────────────────────
    private fun saveAcknowledgement() {
        val empId       = ackEmployeeId ?: return
        val currentType = ackRecordType ?: "CLOCK_IN"
        val docId       = (if (currentType == "CLOCK_IN") activeClockInAck else activeClockOutAck)?.first ?: return
        val now   = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

        FirebaseFirestore.getInstance()
            .collection("acknowledgements")
            .add(hashMapOf(
                "employeeId"     to empId,
                "announcementId" to docId,
                "date"           to today,
                "timestamp"      to Timestamp.now(),
                "recordType"     to currentType          // "CLOCK_IN" or "CLOCK_OUT"
            ))
            .addOnSuccessListener { Log.d(TAG, "ACK saved for $empId ($currentType)") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to save ACK", e) }

        postToSheets("ACKNOWLEDGE", empId, now, ackType = currentType)

        // Clear overlay state
        showAckOverlay      = false
        ackAnnouncementText = null
        ackEmployeeId       = null
        ackRecordType       = null

        // Clock-in ACK → proceed to lunch survey; Clock-out ACK → reset
        if (currentType == "CLOCK_IN") {
            lunchSurveyEmployeeId = empId
            showLunchSurvey       = true
        } else {
            resetKiosk()
        }
    }

    // ── Reset kiosk ────────────────────────────────────────────────────
    private fun resetKiosk(toastMessage: String? = null) {
        toastMessage?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        showButtons           = false
        showLunchSurvey       = false
        showAckOverlay        = false
        blockedMessage        = null
        ackAnnouncementText   = null
        ackEmployeeId         = null
        ackRecordType         = null
        statusText            = "QR ကုဒ် ပြပါ"
        pendingEmployeeId     = null
        pendingEmployeeName   = null
        lunchSurveyEmployeeId = null
        if (::qrAnalyzer.isInitialized) qrAnalyzer.resumeScanning()
        resetStandbyTimer()
    }

    // ── Google Sheets POST ─────────────────────────────────────────────
    private fun postToSheets(
        action: String,
        employeeId: String,
        timestampMs: Long,
        hasLunch: Boolean? = null,
        name: String? = null,
        ackType: String? = null
    ) {
        if (SHEETS_URL.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
                val body = buildString {
                    append("{\"action\":\"$action\",")
                    append("\"employeeId\":\"$employeeId\",")
                    append("\"date\":\"$date\",")
                    append("\"timestamp\":\"$time\"")
                    if (hasLunch != null) append(",\"hasLunch\":$hasLunch")
                    if (name    != null) append(",\"name\":\"${name.replace("\"", "\\\"")}\"")
                    if (ackType != null) append(",\"ackType\":\"$ackType\"")
                    append("}")
                }
                httpPost(SHEETS_URL, body)
            } catch (e: Exception) {
                Log.e(TAG, "postToSheets failed: ${e.message}")
            }
        }
    }

    private fun httpPost(urlStr: String, body: String) {
        var conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput     = true
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code     = conn.responseCode
        val location = conn.getHeaderField("Location")
        conn.disconnect()
        if ((code == HttpURLConnection.HTTP_MOVED_TEMP || code == 302) && location != null) {
            val conn2 = URL(location).openConnection() as HttpURLConnection
            conn2.requestMethod = "POST"
            conn2.doOutput     = true
            conn2.instanceFollowRedirects = false
            conn2.setRequestProperty("Content-Type", "application/json")
            conn2.outputStream.use { it.write(body.toByteArray()) }
            conn2.inputStream.use { /* consume to complete */ }
            conn2.disconnect()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "KHH_Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val SHEETS_URL =
            "https://script.google.com/macros/s/AKfycbxxHlKaXEbjsHS7AfKISfuQyStseB_wE7UY-X0Swdcqj0cmdyfN4V4oIJ_IZagClUobVw/exec"
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
    showAckOverlay: Boolean,
    ackAnnouncementText: String?,
    ackRecordType: String?,
    blockedMessage: String?,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    onLunchYes: () -> Unit,
    onLunchNo: () -> Unit,
    onWakeUp: () -> Unit,
    onAcknowledge: () -> Unit,
    showExitDialog: Boolean,
    exitPinInput: String,
    exitPinError: Boolean,
    onBrandingTap: () -> Unit,
    onExitPinKey: (String) -> Unit,
    onExitPinBackspace: () -> Unit,
    onExitPinDismiss: () -> Unit
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

        val clockTextSize  = if (isTablet) 80f    else if (isLandscape) 42f    else 60f
        val dateTextSize   = if (isTablet) 24f    else if (isLandscape) 14f    else 18f
        val announceFontSz = if (isTablet) 28.sp  else 22.sp
        val statusFontSz   = if (isTablet) 22.sp  else if (isLandscape) 15.sp  else 18.sp
        val scannerSize    = if (isTablet) 340.dp else if (isLandscape) 210.dp else 280.dp
        val btnWidth       = if (isTablet) 200.dp else if (isLandscape) 130.dp else 155.dp
        val btnHeight      = if (isTablet) 90.dp  else if (isLandscape) 58.dp  else 72.dp
        val btnFontSz      = if (isTablet) 26.sp  else if (isLandscape) 16.sp  else 20.sp
        val successIconSz  = if (isTablet) 160.dp else if (isLandscape) 80.dp  else 120.dp
        val successFontSz  = if (isTablet) 38.sp  else if (isLandscape) 24.sp  else 30.sp
        val lunchBtnWidth  = if (isTablet) 190.dp else if (isLandscape) 120.dp else 148.dp
        val lunchEmojiSz   = if (isTablet) 80.sp  else if (isLandscape) 44.sp  else 60.sp
        val lunchQnFontSz  = if (isTablet) 32.sp  else if (isLandscape) 20.sp  else 26.sp
        val lunchBtnFontSz = if (isTablet) 26.sp  else if (isLandscape) 16.sp  else 22.sp
        val overlayWidth   = if (isTablet) 0.7f   else if (isLandscape) 0.75f  else 0.9f
        val overlayVPad    = if (isLandscape && !isTablet) 18.dp else 44.dp
        val ackBtnWidth    = if (isTablet) 280.dp else if (isLandscape) 180.dp else 220.dp
        val ackBtnHeight   = if (isTablet) 90.dp  else if (isLandscape) 54.dp  else 72.dp
        val ackBtnFontSz   = if (isTablet) 26.sp  else if (isLandscape) 16.sp  else 20.sp
        val ackMsgFontSz   = if (isTablet) 26.sp  else if (isLandscape) 16.sp  else 20.sp
        val overlayEmojiSz = if (isTablet) 64.sp  else if (isLandscape) 36.sp  else 48.sp
        val overlayLabelSz = if (isTablet) 20.sp  else 15.sp

        // ── Camera preview ────────────────────────────────────────────
        AndroidView(factory = { cameraPreviewView }, modifier = Modifier.fillMaxSize())

        // ── Vignettes ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth().height(320.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xF2040D1A), Color.Transparent)))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth().height(320.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2040D1A))))
        )

        // ── Announcement bar ─────────────────────────────────────────
        if (hasAnnouncements) {
            val announcePulse = rememberInfiniteTransition(label = "dot")
            val dotAlpha by announcePulse.animateFloat(
                initialValue  = 0.35f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label         = "dotAlpha"
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0xF5040D1A))
                    .padding(horizontal = 22.dp, vertical = 13.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF38BDF8).copy(alpha = dotAlpha), CircleShape)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text       = announcements[currentAnnouncementIndex.coerceIn(0, announcements.size - 1)],
                    color      = Color(0xFFE2E8F0),
                    fontSize   = announceFontSz,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f)
                )
                if (announcements.size > 1) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text     = "${currentAnnouncementIndex + 1}/${announcements.size}",
                        color    = Color(0xFF334155),
                        fontSize = 13.sp
                    )
                }
            }
        }

        // ── Main layout (landscape = 3-column Row, portrait = stacked) ─
        if (isLandscape) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (hasAnnouncements) 58.dp else 0.dp)
            ) {
                // Left: Branding + Clock
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0x1538BDF8), RoundedCornerShape(50.dp))
                            .border(1.dp, Color(0x3038BDF8), RoundedCornerShape(50.dp))
                            .clickable(onClick = onBrandingTap)
                            .padding(horizontal = 16.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text          = "KHH  ATTENDANCE",
                            color         = Color(0xFF38BDF8),
                            fontSize      = 10.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 4.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    AndroidView(
                        factory = { ctx -> TextClock(ctx).apply {
                            format12Hour = "hh:mm a"
                            setTextColor(AndroidColor.WHITE)
                            typeface     = Typeface.DEFAULT_BOLD
                            setShadowLayer(12f, 0f, 4f, AndroidColor.parseColor("#44000000"))
                        }},
                        update = { it.textSize = clockTextSize }
                    )
                    AndroidView(
                        factory = { ctx -> TextClock(ctx).apply {
                            format12Hour = "EEEE, MMMM dd"
                            setTextColor(AndroidColor.parseColor("#64748B"))
                            setShadowLayer(4f, 0f, 1f, AndroidColor.BLACK)
                        }},
                        update = { it.textSize = dateTextSize }
                    )
                }

                // Center: Scanner
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    ScannerFrame(frameSize = scannerSize)
                }

                // Right: Status chip (idle only; selection handled by full-screen overlay)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    if (!showButtons && successMessage == null && lunchSuccessMessage == null
                        && !showLunchSurvey && blockedMessage == null
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .background(
                                    Brush.linearGradient(listOf(Color(0xF0040D1A), Color(0xF00A1E38))),
                                    RoundedCornerShape(50.dp)
                                )
                                .border(1.dp, Color(0x1A38BDF8), RoundedCornerShape(50.dp))
                                .padding(horizontal = 28.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text       = statusText,
                                color      = Color(0xFFCBD5E1),
                                fontSize   = statusFontSz,
                                fontWeight = FontWeight.Medium,
                                textAlign  = TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            // ── Portrait: Branding + Clock ────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (hasAnnouncements) 74.dp else 44.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0x1538BDF8), RoundedCornerShape(50.dp))
                        .border(1.dp, Color(0x3038BDF8), RoundedCornerShape(50.dp))
                        .clickable(onClick = onBrandingTap)
                        .padding(horizontal = 16.dp, vertical = 5.dp)
                ) {
                    Text(
                        text          = "KHH  ATTENDANCE",
                        color         = Color(0xFF38BDF8),
                        fontSize      = 10.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 4.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                AndroidView(
                    factory = { ctx -> TextClock(ctx).apply {
                        format12Hour = "hh:mm a"
                        setTextColor(AndroidColor.WHITE)
                        typeface     = Typeface.DEFAULT_BOLD
                        setShadowLayer(12f, 0f, 4f, AndroidColor.parseColor("#44000000"))
                    }},
                    update = { it.textSize = clockTextSize }
                )
                AndroidView(
                    factory = { ctx -> TextClock(ctx).apply {
                        format12Hour = "EEEE, MMMM dd"
                        setTextColor(AndroidColor.parseColor("#64748B"))
                        setShadowLayer(4f, 0f, 1f, AndroidColor.BLACK)
                    }},
                    update = { it.textSize = dateTextSize }
                )
            }

            // ── Portrait: Scanner frame ───────────────────────────────
            ScannerFrame(
                modifier  = Modifier.align(Alignment.Center).offset(y = 24.dp),
                frameSize = scannerSize
            )

            // ── Portrait: Status chip (idle) ──────────────────────────
            if (!showButtons && successMessage == null && lunchSuccessMessage == null
                && !showLunchSurvey && blockedMessage == null
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 52.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xF0040D1A), Color(0xF00A1E38))),
                            RoundedCornerShape(50.dp)
                        )
                        .border(1.dp, Color(0x1A38BDF8), RoundedCornerShape(50.dp))
                        .padding(horizontal = 36.dp, vertical = 14.dp)
                ) {
                    Text(
                        text       = statusText,
                        color      = Color(0xFFCBD5E1),
                        fontSize   = statusFontSz,
                        fontWeight = FontWeight.Medium,
                        textAlign  = TextAlign.Center
                    )
                }
            }

        }

        // ── Clock-in / Clock-out selection overlay ────────────────────
        if (showButtons && successMessage == null && !showLunchSurvey && blockedMessage == null) {
            val selBtnWidth  = if (isTablet) 280.dp else 200.dp
            val selBtnHeight = if (isTablet) 120.dp else 92.dp
            val selBtnFont   = if (isTablet) 32.sp  else 22.sp
            val selNameFont  = if (isTablet) 30.sp  else 22.sp
            val selSubFont   = if (isTablet) 16.sp  else 13.sp
            val namePart     = statusText.substringBefore("\n")

            Box(
                modifier         = Modifier.fillMaxSize().background(Color(0xED040D1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(overlayWidth)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF0D1B2E), Color(0xFF061528))),
                            RoundedCornerShape(32.dp)
                        )
                        .border(1.dp, Color(0x201E3A5F), RoundedCornerShape(32.dp))
                        .padding(horizontal = 40.dp, vertical = overlayVPad)
                ) {
                    Text("👋", fontSize = overlayEmojiSz)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text       = namePart,
                        color      = Color(0xFFF1F5F9),
                        fontSize   = selNameFont,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text       = "ကျေးဇူးပြု၍ ရွေးချယ်ပါ",
                        color      = Color(0xFF475569),
                        fontSize   = selSubFont,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(36.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = selBtnWidth, height = selBtnHeight)
                                .background(
                                    Brush.verticalGradient(listOf(Color(0xFF16A34A), Color(0xFF14532D))),
                                    RoundedCornerShape(22.dp)
                                )
                                .clickable(onClick = onClockIn)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("↓", fontSize = if (isTablet) 26.sp else 20.sp, color = Color(0xFFBBF7D0), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("ဝင်ချိန်", fontSize = selBtnFont, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = selBtnWidth, height = selBtnHeight)
                                .background(
                                    Brush.verticalGradient(listOf(Color(0xFFDC2626), Color(0xFF7F1D1D))),
                                    RoundedCornerShape(22.dp)
                                )
                                .clickable(onClick = onClockOut)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("↑", fontSize = if (isTablet) 26.sp else 20.sp, color = Color(0xFFFECACA), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("ထွက်ချိန်", fontSize = selBtnFont, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // ── Success overlay ───────────────────────────────────────────
        if (successMessage != null) {
            Box(
                modifier         = Modifier.fillMaxSize().background(Color(0xEC040D1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF0A1E38), Color(0xFF061528))),
                            RoundedCornerShape(32.dp)
                        )
                        .border(1.dp, Color(0x5010B981), RoundedCornerShape(32.dp))
                        .padding(horizontal = 56.dp, vertical = overlayVPad)
                ) {
                    Icon(
                        imageVector        = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint               = Color(0xFF10B981),
                        modifier           = Modifier.size(successIconSz)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text       = successMessage,
                        color      = Color(0xFFF1F5F9),
                        fontSize   = successFontSz,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center
                    )
                }
            }
        }

        // ── Blocked overlay ───────────────────────────────────────────
        if (blockedMessage != null) {
            Box(
                modifier         = Modifier.fillMaxSize().background(Color(0xF0040D1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(overlayWidth)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF0A1E38), Color(0xFF061528))),
                            RoundedCornerShape(32.dp)
                        )
                        .border(1.dp, Color(0x50EF4444), RoundedCornerShape(32.dp))
                        .padding(horizontal = 40.dp, vertical = overlayVPad)
                ) {
                    Text("⛔", fontSize = overlayEmojiSz)
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text       = blockedMessage,
                        color      = Color(0xFFF87171),
                        fontSize   = if (isTablet) 28.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center
                    )
                }
            }
        }

        // ── Lunch thank-you ───────────────────────────────────────────
        if (lunchSuccessMessage != null) {
            Box(
                modifier         = Modifier.fillMaxSize().background(Color(0xEC040D1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF0A1E38), Color(0xFF061528))),
                            RoundedCornerShape(32.dp)
                        )
                        .border(1.dp, Color(0x3010B981), RoundedCornerShape(32.dp))
                        .padding(horizontal = 56.dp, vertical = overlayVPad)
                ) {
                    Text("✅", fontSize = 80.sp)
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text       = lunchSuccessMessage,
                        color      = Color(0xFFF1F5F9),
                        fontSize   = if (isTablet) 30.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center
                    )
                }
            }
        }

        // ── Standby ───────────────────────────────────────────────────
        if (isStandby) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF040D1A))
                    .clickable { onWakeUp() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .background(Color(0x101E3A8A), RoundedCornerShape(50.dp))
                            .border(1.dp, Color(0x181E3A8A), RoundedCornerShape(50.dp))
                            .clickable(onClick = onBrandingTap)
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text          = "KHH  ATTENDANCE",
                            color         = Color(0xFF1E3A5F),
                            fontSize      = 10.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 4.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    AndroidView(
                        factory = { ctx -> TextClock(ctx).apply {
                            format12Hour = "hh:mm a"
                            setTextColor(AndroidColor.parseColor("#1E3A8A"))
                            typeface     = Typeface.DEFAULT_BOLD
                        }},
                        update = { it.textSize = clockTextSize }
                    )
                    AndroidView(
                        factory = { ctx -> TextClock(ctx).apply {
                            format12Hour = "EEEE, MMMM dd"
                            setTextColor(AndroidColor.parseColor("#172554"))
                        }},
                        update = { it.textSize = dateTextSize }
                    )
                    Spacer(modifier = Modifier.height(64.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0x08FFFFFF), RoundedCornerShape(50.dp))
                            .border(1.dp, Color(0x101E3A5F), RoundedCornerShape(50.dp))
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text       = "QR ကုဒ် ပြ၍ ဆက်လက်ဆောင်ရွက်ပါ",
                            color      = Color(0xFF1D3461),
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ── ACK overlay ───────────────────────────────────────────────
        if (showAckOverlay && ackAnnouncementText != null) {
            val isClockOut     = ackRecordType == "CLOCK_OUT"
            val ackLabel       = if (isClockOut) "ထွက်ချိန် · အသိပေးကြေညာချက်"
                                 else             "ဝင်ချိန် · အသိပေးကြေညာချက်"
            val ackLabelColor  = if (isClockOut) Color(0xFFF87171) else Color(0xFF38BDF8)
            val ackBtnGradient = if (isClockOut)
                Brush.verticalGradient(listOf(Color(0xFFDC2626), Color(0xFF7F1D1D)))
                else Brush.verticalGradient(listOf(Color(0xFF16A34A), Color(0xFF14532D)))
            val ackBorderColor = if (isClockOut) Color(0x40EF4444) else Color(0x4010B981)

            Box(
                modifier         = Modifier.fillMaxSize().background(Color(0xED040D1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(overlayWidth)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF0D1B2E), Color(0xFF061528))),
                            RoundedCornerShape(32.dp)
                        )
                        .border(1.dp, ackBorderColor, RoundedCornerShape(32.dp))
                        .padding(horizontal = 36.dp, vertical = overlayVPad)
                ) {
                    Text("📋", fontSize = overlayEmojiSz)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text          = ackLabel,
                        color         = ackLabelColor,
                        fontSize      = overlayLabelSz,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF040D1A), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0x1A1E3A5F), RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Text(
                            text       = ackAnnouncementText,
                            color      = Color(0xFFE2E8F0),
                            fontSize   = ackMsgFontSz,
                            fontWeight = FontWeight.Medium,
                            textAlign  = TextAlign.Center,
                            modifier   = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(width = ackBtnWidth, height = ackBtnHeight)
                            .background(ackBtnGradient, RoundedCornerShape(18.dp))
                            .clickable(onClick = onAcknowledge)
                    ) {
                        Text(
                            "✅  သိရှိပါသည်",
                            fontSize   = ackBtnFontSz,
                            fontWeight = FontWeight.Bold,
                            color      = Color.White
                        )
                    }
                }
            }
        }

        // ── Exit passcode overlay ─────────────────────────────────────
        if (showExitDialog) {
            val numPadSize = if (isTablet) 80.dp else 64.dp
            val numPadFont = if (isTablet) 24.sp else 20.sp
            val dotSize    = if (isTablet) 18.dp else 14.dp

            Box(
                modifier         = Modifier.fillMaxSize().background(Color(0xF2040D1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(overlayWidth)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF0D1B2E), Color(0xFF061528))),
                            RoundedCornerShape(32.dp)
                        )
                        .border(1.dp, Color(0x201E3A5F), RoundedCornerShape(32.dp))
                        .padding(horizontal = 32.dp, vertical = overlayVPad)
                ) {
                    Text("🔐", fontSize = if (isTablet) 52.sp else 40.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text       = "Admin Access",
                        color      = Color(0xFFF1F5F9),
                        fontSize   = if (isTablet) 22.sp else 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text     = "Enter passcode to exit",
                        color    = Color(0xFF475569),
                        fontSize = if (isTablet) 14.sp else 12.sp
                    )
                    Spacer(modifier = Modifier.height(28.dp))

                    // PIN dots
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        repeat(EXIT_PIN.length) { i ->
                            val filled = i < exitPinInput.length
                            val color  = when {
                                exitPinError && filled -> Color(0xFFEF4444)
                                filled                -> Color(0xFF38BDF8)
                                else                  -> Color(0xFF1E3A5F)
                            }
                            Box(modifier = Modifier.size(dotSize).background(color, CircleShape))
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Numpad
                    val keys = listOf("1","2","3","4","5","6","7","8","9","⌫","0","")
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        keys.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { key ->
                                    if (key.isEmpty()) {
                                        Spacer(modifier = Modifier.size(numPadSize))
                                    } else {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(numPadSize)
                                                .background(Color(0xFF0A1E38), RoundedCornerShape(14.dp))
                                                .border(1.dp, Color(0x151E3A5F), RoundedCornerShape(14.dp))
                                                .clickable {
                                                    if (key == "⌫") onExitPinBackspace()
                                                    else onExitPinKey(key)
                                                }
                                        ) {
                                            Text(
                                                text       = key,
                                                color      = Color(0xFFF1F5F9),
                                                fontSize   = numPadFont,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text       = "Cancel",
                        color      = Color(0xFF334155),
                        fontSize   = if (isTablet) 15.sp else 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier   = Modifier.clickable(onClick = onExitPinDismiss)
                    )
                }
            }
        }

        // ── Lunch survey ──────────────────────────────────────────────
        if (showLunchSurvey) {
            Box(
                modifier         = Modifier.fillMaxSize().background(Color(0xEC040D1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF0D1B2E), Color(0xFF061528))),
                            RoundedCornerShape(32.dp)
                        )
                        .border(1.dp, Color(0x1A38BDF8), RoundedCornerShape(32.dp))
                        .padding(horizontal = 48.dp, vertical = overlayVPad)
                ) {
                    Text("🍱", fontSize = lunchEmojiSz)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text       = "ယနေ့ နေ့လည်စာ",
                        color      = Color(0xFF64748B),
                        fontSize   = if (isTablet) 18.sp else 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text       = "စားမှာလား?",
                        color      = Color(0xFFF1F5F9),
                        fontSize   = lunchQnFontSz,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = lunchBtnWidth, height = btnHeight)
                                .background(
                                    Brush.verticalGradient(listOf(Color(0xFF16A34A), Color(0xFF14532D))),
                                    RoundedCornerShape(18.dp)
                                )
                                .clickable(onClick = onLunchYes)
                        ) {
                            Text("ဟုတ်ကဲ့", fontSize = lunchBtnFontSz, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = lunchBtnWidth, height = btnHeight)
                                .background(
                                    Brush.verticalGradient(listOf(Color(0xFFDC2626), Color(0xFF7F1D1D))),
                                    RoundedCornerShape(18.dp)
                                )
                                .clickable(onClick = onLunchNo)
                        ) {
                            Text("မစားပါ", fontSize = lunchBtnFontSz, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScannerFrame(modifier: Modifier = Modifier, frameSize: Dp = 280.dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")

    val scanProgress by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanLine"
    )

    val cornerAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.5f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "corners"
    )

    val cornerLen   = (frameSize.value * 0.14f).dp
    val stroke      = 5.dp
    val cornerColor = Color(0xFF38BDF8).copy(alpha = cornerAlpha)

    Box(modifier = modifier.size(frameSize)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x0838BDF8), RoundedCornerShape(20.dp))
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lineY    = scanProgress * size.height
            val gradient = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color(0x7038BDF8), Color.Transparent),
                startY = lineY - 70f,
                endY   = lineY + 70f
            )
            drawRect(brush = gradient, size = size)
        }
        // Top-left
        Box(Modifier.align(Alignment.TopStart).width(cornerLen).height(stroke).background(cornerColor))
        Box(Modifier.align(Alignment.TopStart).width(stroke).height(cornerLen).background(cornerColor))
        // Top-right
        Box(Modifier.align(Alignment.TopEnd).width(cornerLen).height(stroke).background(cornerColor))
        Box(Modifier.align(Alignment.TopEnd).width(stroke).height(cornerLen).background(cornerColor))
        // Bottom-left
        Box(Modifier.align(Alignment.BottomStart).width(cornerLen).height(stroke).background(cornerColor))
        Box(Modifier.align(Alignment.BottomStart).width(stroke).height(cornerLen).background(cornerColor))
        // Bottom-right
        Box(Modifier.align(Alignment.BottomEnd).width(cornerLen).height(stroke).background(cornerColor))
        Box(Modifier.align(Alignment.BottomEnd).width(stroke).height(cornerLen).background(cornerColor))
    }
}
