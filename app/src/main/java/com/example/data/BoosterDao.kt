package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BoosterDao {
    @Query("SELECT * FROM user_games ORDER BY id DESC")
    fun getAllGames(): Flow<List<UserGame>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGame(game: UserGame)

    @Delete
    suspend fun deleteGame(game: UserGame)

    @Query("SELECT * FROM user_games WHERE packageName = :packageName LIMIT 1")
    suspend fun getGameByPackage(packageName: String): UserGame?

    @Query("SELECT * FROM boost_logs ORDER BY timestamp DESC LIMIT 50")
    fun getRecentLogs(): Flow<List<BoostLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: BoostLog)

    @Query("DELETE FROM boost_logs")
    suspend fun clearLogHistory()
}
