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

    private var isScanningPaused = false

    // NEW: Memory variable to remember if a face was seen recently
    private var lastFaceSeenTimestamp = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isScanningPaused) {
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

                // If we see a face in this frame, update our memory timestamp!
                if (!faces.isNullOrEmpty()) {
                    lastFaceSeenTimestamp = System.currentTimeMillis()
                }

                val qrValue = barcodes?.firstOrNull()?.rawValue
                if (qrValue != null) {

                    // GRACE PERIOD: Was a face seen anytime in the last 2 seconds (2000 ms)?
                    if (System.currentTimeMillis() - lastFaceSeenTimestamp < 2000) {
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

    fun resumeScanning() {
        isScanningPaused = false
    }
}