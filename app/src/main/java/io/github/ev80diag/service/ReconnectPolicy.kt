package io.github.ev80diag.service

import io.github.ev80diag.pipeline.SourceResult
import io.github.ev80diag.settings.RecordingMode

/** When and whether the recorder reconnects or stops (spec §11: automatic vs only while open). */
object ReconnectPolicy {
    sealed interface Decision {
        data class Connect(val delayMs: Long) : Decision
        data object Stop : Decision
    }

    private const val BASE_MS = 5_000L
    private const val MAX_MS = 60_000L
    private const val WHILE_OPEN_GIVE_UP_MS = 10 * 60_000L

    fun decide(last: SourceResult, attempt: Int): Decision = when (last) {
        SourceResult.NONE -> Decision.Connect(0)
        SourceResult.REFUSED -> Decision.Stop
        else -> Decision.Connect(minOf(MAX_MS, BASE_MS shl attempt.coerceIn(0, 10)))
    }

    /** "Only while the app is open" ends once the van has been off this long, whether or not the adapter link is up. */
    fun stopBecauseVanAsleep(mode: RecordingMode, vanOffMs: Long): Boolean =
        mode == RecordingMode.WHILE_OPEN && vanOffMs >= WHILE_OPEN_GIVE_UP_MS
}
