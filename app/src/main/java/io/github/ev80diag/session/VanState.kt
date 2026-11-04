package io.github.ev80diag.session

import io.github.ev80diag.can.CanFrame
import io.github.ev80diag.signals.Decoder

/**
 * What the van is doing, derived from verified signals only (see /claude.md, 2026-10-31 correction):
 * 0x270/0x55C mean "gear out of P" and are NOT used for READY.
 */
enum class PowerState {
    OFF,
    /** Ignition on, high voltage off. */ ON,
    /** High-voltage contactors closed, drive-side IDs alive. */ READY,
    /** BMS awake while plugged in, nothing charging yet (only the BMS ID set is on the bus). */ PLUGGED,
    CHARGING_AC, CHARGING_DC, CHARGE_COMPLETE, STOPPED_BY_BMS
}

data class VanState(val power: PowerState, val gear: Char?, val lastFrameMs: Long)

class VanStateTracker {
    private var lastBms = Long.MIN_VALUE
    private var lastDrive = Long.MIN_VALUE
    private var lastAny = Long.MIN_VALUE
    private var bmsState = -1
    private var hvFlags = 0
    private var dcLinkV = 0.0
    private var gear: Char? = null
    private var held = PowerState.OFF

    fun onFrame(f: CanFrame, decoded: Map<String, Double>): VanState {
        lastAny = f.timestamp
        if (f.id == ID_BMS_STATE) lastBms = f.timestamp
        if (f.id == ID_DRIVE) lastDrive = f.timestamp
        decoded["bms_state"]?.let { bmsState = it.toInt() }
        decoded["hv_flags"]?.let { hvFlags = it.toInt() }
        decoded["dc_link_v"]?.let { dcLinkV = it }
        decoded["gear"]?.let { gear = gearChar(it.toInt()) }
        return state(f.timestamp)
    }

    fun onTick(nowMs: Long): VanState = state(nowMs)

    private fun state(now: Long): VanState {
        val bmsFresh = lastBms != Long.MIN_VALUE && now - lastBms <= BMS_FRESH_MS
        val driveFresh = lastDrive != Long.MIN_VALUE && now - lastDrive <= DRIVE_FRESH_MS
        val power = when {
            lastAny == Long.MIN_VALUE || now - lastAny > OFF_AFTER_MS -> PowerState.OFF
            bmsFresh && bmsState == BMS_DC -> PowerState.CHARGING_DC
            bmsFresh && bmsState == BMS_AC -> PowerState.CHARGING_AC
            bmsFresh && bmsState == BMS_COMPLETE -> PowerState.CHARGE_COMPLETE
            bmsFresh && bmsState == BMS_STOPPED -> PowerState.STOPPED_BY_BMS
            driveFresh -> if (hvFlags and HV_CONTACTORS != 0 && dcLinkV > READY_DC_LINK_V) PowerState.READY else PowerState.ON
            bmsFresh -> PowerState.PLUGGED
            else -> held                                   // a short silence (wake/sleep) keeps the last state
        }
        held = power
        return VanState(power, gear, lastAny)
    }

    private fun gearChar(v: Int): Char? = when (v) { 5 -> 'D'; 6 -> 'N'; 7 -> 'R'; 8 -> 'P'; else -> null }

    private companion object {
        const val ID_BMS_STATE = 0x200
        const val ID_DRIVE = 0x300          // 100 Hz motor frame: present whenever the drive side is alive
        const val BMS_FRESH_MS = 5_000L
        const val DRIVE_FRESH_MS = 2_000L
        const val OFF_AFTER_MS = 10_000L
        const val BMS_DC = 0x16
        const val BMS_AC = 0x15
        const val BMS_COMPLETE = 0x1A
        const val BMS_STOPPED = 0x12
        const val HV_CONTACTORS = 0x04
        const val READY_DC_LINK_V = 300.0
    }
}
