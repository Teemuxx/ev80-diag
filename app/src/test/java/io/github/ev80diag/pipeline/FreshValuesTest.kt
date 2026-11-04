package io.github.ev80diag.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshValuesTest {
    private val latest = mapOf("motor_temp" to 36.0, "v12" to 13.9, "pack_v" to 357.7, "cell_min_no" to 46.0, "cell_min_mv" to 3413.0)

    @Test fun silentIds_dropWhileOthersFlow() {
        // charging: the BMS set (0x550, 0x530) is on the bus, the drive side (0x302, 0x303) went quiet an hour ago
        val seen = mapOf(0x302 to 0L, 0x303 to 0L, 0x550 to 3_600_000L, 0x530 to 3_599_000L)
        assertEquals(setOf("pack_v", "cell_min_no", "cell_min_mv"), FreshValues.filter(latest, seen, newestTs = 3_600_000L).keys)
    }

    @Test fun wholeVanAsleep_keepsEverything() {
        // no frames at all since: the newest frame time is the last one seen, nothing is older than it by much
        val seen = mapOf(0x302 to 999_000L, 0x303 to 999_500L, 0x550 to 1_000_000L, 0x530 to 1_000_000L)
        assertEquals(latest.keys, FreshValues.filter(latest, seen, newestTs = 1_000_000L).keys)
    }
}
