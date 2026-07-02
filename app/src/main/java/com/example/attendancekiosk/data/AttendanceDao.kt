package com.example.attendancekiosk.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update // ADD THIS IMPORT

@Dao
interface AttendanceDao {
    @Insert
    suspend fun insertRecord(record: AttendanceRecord)

    // NEW: Update an existing record
    @Update
    suspend fun updateRecord(record: AttendanceRecord)

    @Query("SELECT * FROM attendance_logs ORDER BY timestamp DESC")
    suspend fun getAllRecords(): List<AttendanceRecord>

    @Query("SELECT * FROM attendance_logs WHERE isSynced = 0")
    suspend fun getUnsyncedRecords(): List<AttendanceRecord>

    @Query("SELECT * FROM attendance_logs WHERE employeeId = :empId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecordForEmployee(empId: String): AttendanceRecord?

    @Query("SELECT * FROM attendance_logs WHERE employeeId = :empId AND recordType = 'CLOCK_IN' AND timestamp >= :startOfDay AND timestamp < :endOfDay LIMIT 1")
    suspend fun getClockInForDay(empId: String, startOfDay: Long, endOfDay: Long): AttendanceRecord?

    @Query("SELECT * FROM attendance_logs WHERE employeeId = :empId AND recordType = 'CLOCK_OUT' AND timestamp >= :startOfDay AND timestamp < :endOfDay LIMIT 1")
    suspend fun getClockOutForDay(empId: String, startOfDay: Long, endOfDay: Long): AttendanceRecord?
}