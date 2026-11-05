package io.github.ev80diag.profile

import io.github.ev80diag.profile.VanRecognizer.recognize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reference values from the fixtures: ET (lx_state_changes, Terra 54) and Lytee (drive 10-23, Terra 60L). */
class VanRecognizerTest {
    private val et = VanPrint(1, 49_551.1, 13_814.0)
    private val lytee = VanPrint(2, 57_509.9, 13_024.0)
    private val both = listOf(et, lytee)

    @Test fun etOdometer_matchesEt() =
        assertEquals(Recognition.Match(1, Evidence.ODOMETER), recognize(both, 49_560.0, null))

    @Test fun lyteeCounter_matchesLytee() =
        assertEquals(Recognition.Match(2, Evidence.CHARGE_COUNTER), recognize(both, null, 13_030.0))

    @Test fun etBothValues_matchesEt() =
        assertEquals(Recognition.Match(1, Evidence.ODOMETER), recognize(both, 49_551.1, 13_814.0))

    @Test fun zeroOdometer_isNoEvidence() =
        assertEquals(Recognition.NoEvidence, recognize(both, 0.0, null))

    @Test fun odometerBelowLast_doesNotFit() =
        assertEquals(Recognition.Unclear(listOf(1)), recognize(listOf(et), 49_000.0, null))

    @Test fun newVan_unseen_matchesWhenNoOtherFits() =
        assertEquals(Recognition.Match(3, Evidence.ODOMETER), recognize(listOf(et, VanPrint(3, null, null)), 57_510.0, null))

    @Test fun newVan_unseen_notChosenWhenKnownVanFits() =
        assertEquals(Recognition.Match(1, Evidence.ODOMETER), recognize(listOf(et, VanPrint(3, null, null)), 49_600.0, null))

    @Test fun twoUnseen_unclear() =
        assertEquals(Recognition.Unclear(listOf(3, 4)), recognize(listOf(VanPrint(3, null, null), VanPrint(4, null, null)), 57_510.0, null))

    @Test fun bothFit_unclear() =
        assertEquals(Recognition.Unclear(listOf(1, 2)),
            recognize(listOf(VanPrint(1, 50_000.0, null), VanPrint(2, 50_100.0, null)), 50_200.0, null))

    @Test fun odoAndCounterDisagree_unclear() =
        assertEquals(Recognition.Unclear(listOf(1, 2)), recognize(both, 49_560.0, 13_030.0))

    @Test fun counterOnlyKnownForOne_odometerDecides() =
        // ET only has a counter stored, Lytee an odometer: the odometer value seen fits Lytee
        assertEquals(Recognition.Match(2, Evidence.ODOMETER),
            recognize(listOf(VanPrint(1, null, 13_814.0), VanPrint(2, 57_509.9, null)), 57_520.0, null))
}
