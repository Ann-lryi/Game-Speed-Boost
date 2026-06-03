package com.example.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

/**
 * Unit tests for BoosterRepository business logic.
 */
class BoosterRepositoryTest {

    @Mock
    private lateinit var mockDao: BoosterDao

    private lateinit var repository: BoosterRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        repository = BoosterRepository(mockDao)
    }

    @Test
    fun `insertGame calls dao insertGame`() = runBlocking {
        val game = UserGame(
            gameName = "Test Game",
            packageName = "com.test.game",
            performanceProfile = "balanced",
            customFps = 60
        )

        repository.insertGame(game)
        verify(mockDao).insertGame(game)
    }

    @Test
    fun `deleteGame calls dao deleteGame`() = runBlocking {
        val game = UserGame(
            id = 1,
            gameName = "Test Game",
            packageName = "com.test.game",
            performanceProfile = "balanced",
            customFps = 60
        )

        repository.deleteGame(game)
        verify(mockDao).deleteGame(game)
    }

    @Test
    fun `insertLog calls dao insertLog`() = runBlocking {
        val log = BoostLog(
            actionName = "Test Action",
            details = "Test details",
            clearedMemoryMb = 100
        )

        repository.insertLog(log)
        verify(mockDao).insertLog(log)
    }

    @Test
    fun `clearLogHistory calls dao clearLogHistory`() = runBlocking {
        repository.clearLogHistory()
        verify(mockDao).clearLogHistory()
    }

    @Test
    fun `getGameByPackage calls dao getGameByPackage`() = runBlocking {
        val packageName = "com.test.game"
        val expectedGame = UserGame(
            id = 1,
            gameName = "Test Game",
            packageName = packageName,
            performanceProfile = "balanced",
            customFps = 60
        )

        `when`(mockDao.getGameByPackage(packageName)).thenReturn(expectedGame)

        val result = repository.getGameByPackage(packageName)

        assertNotNull(result)
        assertEquals(expectedGame.gameName, result?.gameName)
        assertEquals(expectedGame.packageName, result?.packageName)
        verify(mockDao).getGameByPackage(packageName)
    }

    @Test
    fun `UserGame default values are correct`() {
        val game = UserGame(
            gameName = "Test",
            packageName = "com.test"
        )

        assertEquals(0, game.id) // autoGenerate
        assertEquals(0, game.customFps) // default
        assertEquals("balanced", game.performanceProfile) // default
        assertFalse(game.bypassThermal) // default
        assertFalse(game.lockBrightness) // default
    }

    @Test
    fun `BoostLog default timestamp is set`() {
        val beforeTime = System.currentTimeMillis()
        val log = BoostLog(
            actionName = "Test",
            details = "Test details"
        )
        val afterTime = System.currentTimeMillis()

        assertTrue("Timestamp should be set automatically",
            log.timestamp in beforeTime..afterTime)
        assertEquals(0, log.clearedMemoryMb) // default
    }
}
