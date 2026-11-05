package io.github.ev80diag.calc

import io.github.ev80diag.data.KeyValue

/** The saved [SocState] of each van, keys `<field>_<profileId>`. 0.3 kept one un-suffixed set: see [migrateLegacy]. */
class SocStore(private val kv: KeyValue) {
    private val fields = listOf("calibrated", "capacityAh", "consumedAh", "ahSinceAnchor", "lastUpdateTs", "lastAnchorTs",
        "anchorKind", "continuous", "sinceFullContinuous")

    fun load(profileId: Long, defaultCapacityAh: Float = SocState().capacityAh): SocState =
        read(defaultCapacityAh) { "${it}_$profileId" }

    private fun read(defaultCapacityAh: Float, k: (String) -> String) = SocState(
        calibrated = kv.getBoolean(k("calibrated"), false),
        capacityAh = kv.getFloat(k("capacityAh"), defaultCapacityAh),
        consumedAh = kv.getFloat(k("consumedAh"), 0f),
        ahSinceAnchor = kv.getFloat(k("ahSinceAnchor"), 0f),
        lastUpdateTs = kv.getLong(k("lastUpdateTs"), 0L),
        lastAnchorTs = kv.getLong(k("lastAnchorTs"), 0L),
        anchorKind = AnchorKind.entries.getOrElse(kv.getInt(k("anchorKind"), 0)) { AnchorKind.NONE },
        continuous = kv.getBoolean(k("continuous"), true),
        sinceFullContinuous = kv.getBoolean(k("sinceFullContinuous"), false),
    )

    fun save(profileId: Long, s: SocState) {
        val k = { f: String -> "${f}_$profileId" }
        kv.putBoolean(k("calibrated"), s.calibrated)
        kv.putFloat(k("capacityAh"), s.capacityAh)
        kv.putFloat(k("consumedAh"), s.consumedAh)
        kv.putFloat(k("ahSinceAnchor"), s.ahSinceAnchor)
        kv.putLong(k("lastUpdateTs"), s.lastUpdateTs)
        kv.putLong(k("lastAnchorTs"), s.lastAnchorTs)
        kv.putInt(k("anchorKind"), s.anchorKind.ordinal)
        kv.putBoolean(k("continuous"), s.continuous)
        kv.putBoolean(k("sinceFullContinuous"), s.sinceFullContinuous)
    }

    fun delete(profileId: Long) = fields.forEach { kv.remove("${it}_$profileId") }

    /** Moves the single 0.3 state (un-suffixed keys) to [firstProfileId] once. */
    fun migrateLegacy(firstProfileId: Long) {
        if (!kv.contains("lastUpdateTs") && !kv.contains("capacityAh") && !kv.contains("consumedAh")) return
        val legacy = read(SocState().capacityAh) { it }
        save(firstProfileId, legacy)
        fields.forEach(kv::remove)
    }
}
