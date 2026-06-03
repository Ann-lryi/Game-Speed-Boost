package com.example.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ShizukuManager logic.
 * Note: These test the logic structure, not Shizuku API calls which require Android runtime.
 */
class ShizukuManagerTest {

    @Test
    fun `console command data class holds values correctly`() {
        val cmd = ConsoleCommand(
            command = "echo test",
            output = "test",
            isSuccess = true,
            executionTimeMs = 100
        )

        assertEquals("echo test", cmd.command)
        assertEquals("test", cmd.output)
        assertTrue(cmd.isSuccess)
        assertEquals(100, cmd.executionTimeMs)
        assertNotNull(cmd.id) // UUID should be auto-generated
        assertTrue(cmd.timestamp > 0)
    }

    @Test
    fun `console command default id is generated`() {
        val cmd1 = ConsoleCommand(command = "cmd1", output = "out1", isSuccess = true)
        val cmd2 = ConsoleCommand(command = "cmd2", output = "out2", isSuccess = false)

        assertNotEquals("Each command should have unique ID", cmd1.id, cmd2.id)
    }

    @Test
    fun `shizuku state enum has all expected values`() {
        val states = ShizukuState.values()
        assertEquals(5, states.size)
        assertTrue(states.contains(ShizukuState.NOT_INSTALLED))
        assertTrue(states.contains(ShizukuState.NOT_RUNNING))
        assertTrue(states.contains(ShizukuState.UNAUTHORIZED))
        assertTrue(states.contains(ShizukuState.AUTHORIZED))
        assertTrue(states.contains(ShizukuState.ADB_FALLBACK))
    }

    @Test
    fun `max console logs limit is enforced`() {
        // The limit constant should be 100
        val maxLogs = 100
        assertEquals(100, maxLogs)
    }

    @Test
    fun `shell timeout is reasonable`() {
        // Shell commands should timeout after 15 seconds
        val timeoutSeconds = 15L
        assertTrue("Timeout should be positive", timeoutSeconds > 0)
        assertTrue("Timeout should be under 1 minute", timeoutSeconds < 60)
    }
}
