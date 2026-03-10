package com.example.attendancekiosk

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.attendancekiosk.data.AttendanceDatabase
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date // This import fixes the timestamp formatting

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = AttendanceDatabase.getDatabase(applicationContext)
        val dao = db.attendanceDao()
        val firestore = FirebaseFirestore.getInstance()

        // 1. Find all records that haven't been uploaded yet
        val unsyncedRecords = dao.getUnsyncedRecords()

        if (unsyncedRecords.isEmpty()) {
            return Result.success() // Nothing to do!
        }

        return try {
            for (record in unsyncedRecords) {
                // 2. Prepare the data for Firebase (Timestamp is now formatted nicely)
                val logData = hashMapOf(
                    "employeeId" to record.employeeId,
                    "timestamp" to Date(record.timestamp),
                    "recordType" to record.recordType
                )

                // 3. Upload to a collection named "attendance_logs"
                firestore.collection("attendance_logs")
                    .add(logData)
                    .await() // Wait for the upload to finish

                // 4. Mark as synced in our local Room database
                val updatedRecord = record.copy(isSynced = true)
                dao.updateRecord(updatedRecord)

                Log.d("SyncWorker", "Successfully synced record for ${record.employeeId}")
            }
            Result.success()

        } catch (e: Exception) {
            Log.e("SyncWorker", "Error syncing to Firebase. Will retry later.", e)
            Result.retry() // Tells Android to try again when network is better
        }
    }
}