package io.github.ev80diag.ui.screen

import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.signals.SignalDef
import io.github.ev80diag.signals.SignalGroup
import io.github.ev80diag.signals.SignalStatus
import io.github.ev80diag.signals.SignalTable

/** One Details row: the signal, its latest value and the raw payload of its source frame. */
data class DetailRow(val def: SignalDef, val value: Double?, val rawHex: String?)

object DetailRows {
    private val ORDER = listOf(SignalGroup.BATTERY, SignalGroup.CHARGING, SignalGroup.DRIVING, SignalGroup.VEHICLE)

    /**
     * Verified and probable signals by group (unknown ones belong to Experimental, stage 3). [query] matches
     * the shown name (any case), the key, or the CAN ID ("550" or "0x550").
     */
    fun build(state: RecorderState, query: String, nameOf: (SignalDef) -> String,
              table: List<SignalDef> = SignalTable.all): Map<SignalGroup, List<DetailRow>> {
        val q = query.trim().lowercase()
        val idQuery = q.removePrefix("0x")
        fun matches(d: SignalDef) = q.isEmpty() || nameOf(d).lowercase().contains(q) || d.key.contains(q) ||
            "%03x".format(d.canId).contains(idQuery)
        val rows = table.filter { it.status != SignalStatus.UNKNOWN && matches(it) }.map { d ->
            DetailRow(d, state.decoded[d.key], state.raw[d.canId]?.joinToString(" ") { "%02X".format(it) })
        }
        val byGroup = rows.groupBy { it.def.group }
        return ORDER.filter { it in byGroup || q.isEmpty() }.associateWith { byGroup[it].orEmpty() }
    }
}
