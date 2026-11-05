package io.github.ev80diag.ui.tiles

import io.github.ev80diag.data.KeyValue

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

/** One tile layout for all vans (key `tiles`). 0.3 stored one per van (`tiles_<profileId>`): the first van's carries over. */
class TileLayoutStore(private val kv: KeyValue) {
    fun load(): List<TileSpec> = TileLayout.decode(kv.getString(KEY) ?: legacy())
    fun save(tiles: List<TileSpec>) = kv.putString(KEY, TileLayout.encode(tiles))

    private fun legacy(): String? = kv.keys().mapNotNull { k -> k.removePrefix("tiles_").toLongOrNull()?.takeIf { k.startsWith("tiles_") } }
        .minOrNull()?.let { kv.getString("tiles_$it") }

    private companion object { const val KEY = "tiles" }
}
