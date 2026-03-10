package com.example.attendancekiosk.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "attendance_logs")
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val employeeId: String,
    val timestamp: Long,
    val photoPath: String,
    val recordType: String, // "CLOCK_IN" or "CLOCK_OUT"
    val isSynced: Boolean = false
)