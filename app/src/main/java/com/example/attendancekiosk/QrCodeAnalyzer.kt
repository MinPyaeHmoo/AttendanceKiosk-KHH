package com.example.attendancekiosk

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class QrCodeAnalyzer(
    private val onValidScan: (String) -> Unit,
    private val onNoFaceDetected: () -> Unit
) : ImageAnalysis.Analyzer {

    private val barcodeOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    private val barcodeScanner = BarcodeScanning.getClient(barcodeOptions)

    private val faceOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()
    private val faceScanner = FaceDetection.getClient(faceOptions)

    private val EMPLOYEE_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9 _.@,-]{1,49}$")

    @Volatile private var isScanningPaused = false
    @Volatile private var frameCount       = 0
    @Volatile private var frameStride      = 1   // 1 = every frame; 8 = every 8th (standby)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isScanningPaused) {
            imageProxy.close()
            return
        }

        frameCount++
        if (frameCount % frameStride != 0) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // 1. Run both scanners simultaneously
            val faceTask = faceScanner.process(image)
            val barcodeTask = barcodeScanner.process(image)

            // 2. Wait for BOTH tasks to finish before deciding what to do
            Tasks.whenAllComplete(faceTask, barcodeTask).addOnCompleteListener {

                val faces = if (faceTask.isSuccessful) faceTask.result else null
                val barcodes = if (barcodeTask.isSuccessful) barcodeTask.result else null

                val qrValue = barcodes?.firstOrNull()?.rawValue
                if (qrValue != null && EMPLOYEE_ID_REGEX.matches(qrValue)) {
                    // Face must be present in the SAME frame as the QR code — no grace period
                    if (!faces.isNullOrEmpty()) {
                        isScanningPaused = true
                        onValidScan(qrValue)
                    } else {
                        onNoFaceDetected()
                    }
                }

                // CRITICAL: Always close the image proxy after all processing is completely done
                imageProxy.close()
            }
        } else {
            imageProxy.close()
        }
    }

    fun setStandbyMode(enabled: Boolean) {
        frameStride = if (enabled) 8 else 1
        frameCount  = 0
    }

    fun resumeScanning() {
        isScanningPaused = false
    }
}