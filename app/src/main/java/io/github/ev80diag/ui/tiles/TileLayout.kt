package io.github.ev80diag.ui.tiles

import android.content.SharedPreferences

/** The 8 tiles, stored as "KIND" or "CONS_PERIOD:from:to", joined with ';'. Broken slots fall back to [DEFAULT]. */
object TileLayout {
    const val SIZE = 8
    val DEFAULT: List<TileSpec> = listOf(
        TileKind.SOC_APP, TileKind.RANGE_APP, TileKind.SPEED, TileKind.POWER,
        TileKind.CELLS_MIN_MAX, TileKind.CONS_NOW, TileKind.V12, TileKind.ODOMETER,
    ).map { TileSpec(it) }

    fun encode(tiles: List<TileSpec>): String = tiles.joinToString(";") { t ->
        if (t.kind == TileKind.CONS_PERIOD) "${t.kind.name}:${t.fromMs}:${t.toMs}" else t.kind.name
    }

    fun decode(text: String?): List<TileSpec> {
        val parts = text?.split(';').orEmpty()
        return List(SIZE) { i -> parts.getOrNull(i)?.let(::parse) ?: DEFAULT[i] }
    }

    private fun parse(p: String): TileSpec? {
        val f = p.split(':')
        val kind = TileKind.entries.firstOrNull { it.name == f[0] } ?: return null
        if (kind != TileKind.CONS_PERIOD) return TileSpec(kind)
        val from = f.getOrNull(1)?.toLongOrNull() ?: return null
        val to = f.getOrNull(2)?.toLongOrNull() ?: return null
        return TileSpec(kind, from, to)
    }
}

/** Per-profile tile layout in SharedPreferences (key `tiles_<profileId>`). */
class TileLayoutStore(private val prefs: SharedPreferences) {
    fun load(profileId: Long): List<TileSpec> = TileLayout.decode(prefs.getString("tiles_$profileId", null))
    fun save(profileId: Long, tiles: List<TileSpec>) = prefs.edit().putString("tiles_$profileId", TileLayout.encode(tiles)).apply()
}
