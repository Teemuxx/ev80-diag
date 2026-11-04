package io.github.ev80diag.pipeline

import io.github.ev80diag.calc.DriveSnapshot
import io.github.ev80diag.session.PowerState
import io.github.ev80diag.session.SessionType
import io.github.ev80diag.session.VanState

enum class LinkStatus { DISCONNECTED, CONNECTING, CONNECTED, REFUSED, LOST }
enum class SourceResult { NONE, ENDED, REFUSED, FAILED }

data class SessionInfo(val id: Long, val type: SessionType, val startMs: Long)

data class ChargeSnapshot(val powerKw: Double, val chargedKwh: Double, val elapsedMs: Long, val timeToFullMin: Double?)

/** Everything the UI needs, published a few times per second. */
data class RecorderState(
    val link: LinkStatus = LinkStatus.DISCONNECTED,
    val adapterName: String? = null,
    val framesPerSec: Double = 0.0,
    val van: VanState = VanState(PowerState.OFF, null, 0L),
    /** Latest value of every decoded signal, by SignalDef.key. */
    val decoded: Map<String, Double> = emptyMap(),
    val cells: IntArray? = null,
    val temps: IntArray? = null,
    val session: SessionInfo? = null,
    val charge: ChargeSnapshot? = null,
    val droppedByConsumer: Map<String, Long> = emptyMap(),
    val errorLines: Int = 0,
    val framesTotal: Long = 0,
    val socAppPercent: Float? = null,
    /** Latest payload per signal ID (Details "hex"). */
    val raw: Map<Int, ByteArray> = emptyMap(),
    /** The open drive's distance, energy and consumption; null outside a drive. */
    val drive: DriveSnapshot? = null,
    /** Time since the pack current last stepped by ≥ 50 A (spread warning waits 60 s). */
    val msSinceLoadChange: Long? = null,
)
