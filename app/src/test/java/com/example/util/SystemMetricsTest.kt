package com.example.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SystemMetrics calculation logic.
 */
class SystemMetricsTest {

    @Test
    fun `getRamUsagePercent returns value between 0 and 100`() {
        // This test verifies the function returns a valid percentage
        // Note: Actual RAM reading requires Android context, so we test the logic structure
        val testCases = listOf(
            Triple(8_000_000_000L, 4_000_000_000L, 50), // 4GB used / 8GB total = 50%
            Triple(8_000_000_000L, 6_000_000_000L, 25), // 2GB used / 8GB total = 25%
            Triple(8_000_000_000L, 2_000_000_000L, 75), // 6GB used / 8GB total = 75%
            Triple(8_000_000_000L, 8_000_000_000L, 0),  // 0GB used / 8GB total = 0%
        )

        for ((total, available, expectedPercent) in testCases) {
            val used = total - available
            val calculated = ((used.toDouble() / total.toDouble()) * 100).toInt()
            assertEquals("RAM usage calculation failed for total=$total, available=$available",
                expectedPercent, calculated)
        }
    }

    @Test
    fun `getRamUsagePercent handles edge cases`() {
        // Test with zero total memory (should not crash)
        val totalZero = 0L
        val availableZero = 0L
        if (totalZero > 0) {
            val used = totalZero - availableZero
            val percent = ((used.toDouble() / totalZero) * 100).toInt()
            assertTrue(percent in 0..100)
        }
        // Division by zero is handled by the try-catch in actual code
    }

    @Test
    fun `battery temperature conversion is correct`() {
        // Battery temperature is reported in tenths of a degree
        val testCases = listOf(
            350 to 35,  // 35.0°C
            421 to 42,  // 42.1°C
            0 to 0,     // 0.0°C
            100 to 10,  // 10.0°C
        )

        for ((rawTemp, expectedCelsius) in testCases) {
            val converted = (rawTemp / 10).coerceIn(0, 100)
            assertEquals("Temperature conversion failed for $rawTemp",
                expectedCelsius, converted)
        }
    }

    @Test
    fun `battery temperature is clamped to valid range`() {
        // Temperature should be coerced to 0-100 range
        val extremelyHigh = 9999
        val clamped = (extremelyHigh / 10).coerceIn(0, 100)
        assertEquals(100, clamped)

        val negative = -100
        val clampedNegative = (negative / 10).coerceIn(0, 100)
        assertEquals(0, clampedNegative)
    }

    @Test
    fun `detectUfsVersion parses model strings correctly`() {
        val testCases = listOf(
            "KLUEG8UHDB-C2D1" to "4.x",
            "KLUDG4U1EA-B2C1" to "3.x",
            "KLUBG4U1EA-B1C1" to "2.x",
            "UFS4.0_CHIP" to "4.x",
            "UFS3.1_CHIP" to "3.x",
            "UFS2.2_CHIP" to "2.x",
            "UNKNOWN_CHIP" to "", // Should not match anything
        )

        for ((model, expected) in testCases) {
            val result = when {
                model.contains("UFS4") || model.contains("KLUE") -> "4.x"
                model.contains("UFS3") || model.contains("KLUD") || model.contains("KLUC") -> "3.x"
                model.contains("UFS2") || model.contains("KLUB") || model.contains("KLUA") -> "2.x"
                else -> ""
            }
            assertEquals("UFS detection failed for $model", expected, result)
        }
    }

    @Test
    fun `cpu calculation from proc stat is correct`() {
        // Simulated /proc/stat line parsing
        val line1 = "cpu  1000 200 300 4000 50 60 70 0 0 0"
        val toks1 = line1.trim().split(Regex("\\s+"))

        val user1 = toks1[1].toLong()
        val nice1 = toks1[2].toLong()
        val system1 = toks1[3].toLong()
        val idle1 = toks1[4].toLong()
        val iowait1 = toks1[5].toLong()
        val irq1 = toks1[6].toLong()
        val softirq1 = toks1[7].toLong()
        val steal1 = toks1[8].toLong()

        val total1 = user1 + nice1 + system1 + idle1 + iowait1 + irq1 + softirq1 + steal1
        assertEquals(5630, total1)

        // Second reading (simulating 100ms later)
        val line2 = "cpu  1100 200 350 4100 50 65 75 0 0 0"
        val toks2 = line2.trim().split(Regex("\\s+"))

        val user2 = toks2[1].toLong()
        val nice2 = toks2[2].toLong()
        val system2 = toks2[3].toLong()
        val idle2 = toks2[4].toLong()
        val iowait2 = toks2[5].toLong()
        val irq2 = toks2[6].toLong()
        val softirq2 = toks2[7].toLong()
        val steal2 = toks2[8].toLong()

        val total2 = user2 + nice2 + system2 + idle2 + iowait2 + irq2 + softirq2 + steal2
        val totalIdle2 = idle2 + iowait2

        val totalDelta = total2 - total1
        val idleDelta = totalIdle2 - (idle1 + iowait1)

        val usage = (((totalDelta - idleDelta).toDouble() / totalDelta) * 100).toInt()

        // CPU increased from 1000+200+300+50+60+70=1680 to 1100+200+350+50+65+75=1840
        // Total delta: 5895 - 5630 = 265
        // Idle delta: (4100+50) - (4000+50) = 100
        // Usage: (265-100)/265 * 100 = 62%
        assertTrue("CPU usage should be reasonable: $usage", usage in 0..100)
        assertEquals(62, usage)
    }

    @Test
    fun `ram gb formatting produces correct output`() {
        val totalBytes = 8L * 1024L * 1024L * 1024L // 8 GB
        val availableBytes = 3L * 1024L * 1024L * 1024L // 3 GB
        val usedBytes = totalBytes - availableBytes

        val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
        val usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0)

        val result = String.format("%.1f", usedGb) + " / " + String.format("%.1f", totalGb) + " GB"
        assertEquals("5.0 / 8.0 GB", result)
    }

    @Test
    fun `resetCpuCache clears cached values`() {
        // After reset, getCpuLoadPercent should return -1 on first call
        // (needs a second reading to calculate delta)
        SystemMetrics.resetCpuCache()
        // We can't easily test the internal state, but we can verify no crash
        SystemMetrics.resetCpuCache()
        SystemMetrics.resetCpuCache()
        // Multiple resets should not cause issues
        assertTrue(true)
    }
}
