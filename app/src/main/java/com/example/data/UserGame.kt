package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_games")
data class UserGame(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val gameName: String,
    val customFps: Int = 0, // 0 means default, 60, 90, 120
    val performanceProfile: String = "balanced", // "balanced", "ultra", "battery"
    val bypassThermal: Boolean = false,
    val lockBrightness: Boolean = false
)
