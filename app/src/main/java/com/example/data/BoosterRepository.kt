package com.example.data

import kotlinx.coroutines.flow.Flow

class BoosterRepository(private val boosterDao: BoosterDao) {
    val allGames: Flow<List<UserGame>> = boosterDao.getAllGames()
    val recentLogs: Flow<List<BoostLog>> = boosterDao.getRecentLogs()

    suspend fun insertGame(game: UserGame) {
        boosterDao.insertGame(game)
    }

    suspend fun deleteGame(game: UserGame) {
        boosterDao.deleteGame(game)
    }

    suspend fun getGameByPackage(packageName: String): UserGame? {
        return boosterDao.getGameByPackage(packageName)
    }

    suspend fun insertLog(log: BoostLog) {
        boosterDao.insertLog(log)
    }

    suspend fun clearLogHistory() {
        boosterDao.clearLogHistory()
    }
}
