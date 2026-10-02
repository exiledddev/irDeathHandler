package dev.exiledddev.deathhandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImmortalMathTest {

    @Test
    void blocksHitsThatWouldGoBelowTheMinimum() {
        assertTrue(ImmortalMath.wouldDropBelow(20, 0, 19.5, 2));
        assertTrue(ImmortalMath.wouldDropBelow(5, 0, 1000, 2));
        assertTrue(ImmortalMath.wouldDropBelow(2, 0, 0.5, 2));
    }

    @Test
    void letsSmallerHitsThrough() {
        assertFalse(ImmortalMath.wouldDropBelow(20, 0, 18, 2));
        assertFalse(ImmortalMath.wouldDropBelow(10, 0, 4, 2));
    }

    @Test
    void absorptionSoaksDamageFirst() {
        assertFalse(ImmortalMath.wouldDropBelow(4, 8, 9, 2));
        assertTrue(ImmortalMath.wouldDropBelow(4, 8, 11, 2));
    }

    @Test
    void blockedHitsLeaveTheMinimumAndNeverHeal() {
        assertEquals(2, ImmortalMath.protectedHealth(20, 2));
        assertEquals(1, ImmortalMath.protectedHealth(1, 2));
    }
}
