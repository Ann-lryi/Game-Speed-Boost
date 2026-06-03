package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "boost_logs")
data class BoostLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val actionName: String,
    val details: String,
    val clearedMemoryMb: Int = 0
)
