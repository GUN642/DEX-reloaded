package de.chris.dexreloaded.render

/**
 * Lage der Bildschirme. 0, 1, 2 sind die Grundreihe (links, Mitte, rechts). Dazu kommen
 * wahlweise einer oben über der Mitte und je einer links und rechts außen.
 * Spalte und Reihe sind in Bildschirmbreiten bzw. -höhen, Mitte ist (0, 0).
 */
object ScreenLayout {
    const val MAX = 6
    const val TOP = 3
    const val FAR_LEFT = 4
    const val FAR_RIGHT = 5

    /** Zusätzlich zuschaltbare Bildschirme. */
    val EXTRAS = listOf(TOP, FAR_LEFT, FAR_RIGHT)
    val BASE = setOf(0, 1, 2)

    private val COL = intArrayOf(-1, 0, 1, 0, -2, 2)
    private val ROW = intArrayOf(0, 0, 0, 1, 0, 0)

    /** Reihenfolge für Listen: von links nach rechts, oben zuletzt. */
    val ORDER = listOf(FAR_LEFT, 0, 1, 2, FAR_RIGHT, TOP)

    fun col(i: Int) = COL[i]
    fun row(i: Int) = ROW[i]

    fun name(i: Int) = when (i) {
        TOP -> "Bildschirm oben"
        FAR_LEFT -> "Bildschirm links außen"
        FAR_RIGHT -> "Bildschirm rechts außen"
        else -> "Bildschirm ${i + 1}"
    }

    fun shortName(i: Int) = when (i) {
        TOP -> "Oben"
        FAR_LEFT -> "Links"
        FAR_RIGHT -> "Rechts"
        else -> "${i + 1}"
    }

    /** Nachbar von [i] um [dCol] Spalten und [dRow] Reihen, falls er aktiv ist. */
    fun neighbor(i: Int, dCol: Int, dRow: Int, active: Collection<Int>): Int? =
        active.firstOrNull { COL[it] == COL[i] + dCol && ROW[it] == ROW[i] + dRow }

    fun sorted(active: Collection<Int>) = ORDER.filter { it in active }
}
