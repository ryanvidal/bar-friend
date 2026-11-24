package com.ryanvidal.barfriend.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolumeUnitTest {
    @Test
    fun `converts ml to oz using standard 30ml bar oz`() {
        assertEquals(1.5, UnitFormatter.convert(45.0, VolumeUnit.ML, VolumeUnit.OZ), 1e-6)
        assertEquals(0.75, UnitFormatter.convert(22.5, VolumeUnit.ML, VolumeUnit.OZ), 1e-6)
        assertEquals(30.0, UnitFormatter.convert(1.0, VolumeUnit.OZ, VolumeUnit.ML), 1e-6)
    }

    @Test
    fun `converts between cl and ml or oz`() {
        assertEquals(4.5, UnitFormatter.convert(45.0, VolumeUnit.ML, VolumeUnit.CL), 1e-6)
        assertEquals(45.0, UnitFormatter.convert(4.5, VolumeUnit.CL, VolumeUnit.ML), 1e-6)
        assertEquals(1.5, UnitFormatter.convert(4.5, VolumeUnit.CL, VolumeUnit.OZ), 1e-6)
    }

    @Test
    fun `formats oz with bartender fractions`() {
        assertEquals("1½ oz", UnitFormatter.formatIngredientAmount(45.0, null, "ml", VolumeUnit.OZ))
        assertEquals("¾ oz", UnitFormatter.formatIngredientAmount(22.5, null, "ml", VolumeUnit.OZ))
        assertEquals("½ oz", UnitFormatter.formatIngredientAmount(15.0, null, "ml", VolumeUnit.OZ))
        assertEquals("¼ oz", UnitFormatter.formatIngredientAmount(7.5, null, "ml", VolumeUnit.OZ))
        assertEquals("⅔ oz", UnitFormatter.formatIngredientAmount(20.0, null, "ml", VolumeUnit.OZ))
        assertEquals("2 oz", UnitFormatter.formatIngredientAmount(60.0, null, "ml", VolumeUnit.OZ))
    }

    @Test
    fun `scales range and preserves non convertible units`() {
        assertEquals(
            "90 ml",
            UnitFormatter.formatIngredientAmount(45.0, null, "ml", VolumeUnit.ML, scaleFactor = 2.0),
        )
        assertEquals(
            "4 dashes",
            UnitFormatter.formatIngredientAmount(2.0, null, "dashes", VolumeUnit.OZ, scaleFactor = 2.0),
        )
        assertEquals(
            "½-1 oz",
            UnitFormatter.formatIngredientAmount(15.0, 30.0, "ml", VolumeUnit.OZ, scaleFactor = 1.0),
        )
        assertEquals(
            "to taste",
            UnitFormatter.formatIngredientAmount(null, null, "to taste", VolumeUnit.OZ, scaleFactor = 2.0),
        )
    }

    @Test
    fun `detects convertible units case insensitively`() {
        assertTrue(UnitFormatter.isConvertible("ML"))
        assertTrue(UnitFormatter.isConvertible("oz"))
        assertTrue(UnitFormatter.isConvertible(" cl "))
        assertFalse(UnitFormatter.isConvertible("dash"))
        assertFalse(UnitFormatter.isConvertible(null))
    }

    @Test
    fun `formats small positive oz amounts without collapsing to zero`() {
        assertEquals("0.03 oz", UnitFormatter.formatIngredientAmount(1.0, null, "ml", VolumeUnit.OZ))
        assertEquals("<0.01 oz", UnitFormatter.formatIngredientAmount(0.1, null, "ml", VolumeUnit.OZ))
        assertEquals("0.03", UnitFormatter.formatOzFraction(1.0 / 30.0))
        assertEquals("<0.01", UnitFormatter.formatOzFraction(0.001))
    }

    @Test
    fun `deduplicates equal or smaller max amounts and formats zero min with positive max`() {
        assertEquals("1 oz", UnitFormatter.formatIngredientAmount(30.0, 30.0, "ml", VolumeUnit.OZ))
        assertEquals("1 oz", UnitFormatter.formatIngredientAmount(30.0, 15.0, "ml", VolumeUnit.OZ))
        assertEquals("0-1 oz", UnitFormatter.formatIngredientAmount(0.0, 30.0, "ml", VolumeUnit.OZ))
        assertEquals("0-2 dashes", UnitFormatter.formatIngredientAmount(0.0, 2.0, "dashes", VolumeUnit.OZ))
        assertEquals("dashes", UnitFormatter.formatIngredientAmount(0.0, 0.0, "dashes", VolumeUnit.OZ))
        assertEquals("oz", UnitFormatter.formatIngredientAmount(0.0, null, "oz", VolumeUnit.OZ))
    }

    @Test
    fun `guards non-finite values without throwing`() {
        assertNull(UnitFormatter.scaleAmount(Double.NaN, "ml", VolumeUnit.OZ))
        assertNull(UnitFormatter.scaleAmount(Double.POSITIVE_INFINITY, "ml", VolumeUnit.OZ))
        assertNull(UnitFormatter.scaleAmount(30.0, "ml", VolumeUnit.OZ, scaleFactor = Double.NaN))
        assertEquals("0", UnitFormatter.formatOzFraction(Double.NaN))
        assertEquals("0", UnitFormatter.formatOzFraction(Double.POSITIVE_INFINITY))
        assertEquals("0", UnitFormatter.formatOzFraction(-5.0))
        assertEquals("ml", UnitFormatter.formatIngredientAmount(Double.NaN, null, "ml", VolumeUnit.OZ))
        assertEquals("ml", UnitFormatter.formatIngredientAmount(Double.POSITIVE_INFINITY, null, "ml", VolumeUnit.ML))
        assertEquals("1 oz", UnitFormatter.formatIngredientAmount(30.0, Double.POSITIVE_INFINITY, "ml", VolumeUnit.OZ))
    }

    @Test
    fun `formats large and negative decimals without int saturation or scientific notation`() {
        assertEquals(
            "21474836.48 ml",
            UnitFormatter.formatIngredientAmount(21_474_836.48, null, "ml", VolumeUnit.ML),
        )
        assertEquals(
            "10000000 ml",
            UnitFormatter.formatIngredientAmount(10_000_000.0, null, "ml", VolumeUnit.ML),
        )
        assertEquals(
            "-1.5 dashes",
            UnitFormatter.formatIngredientAmount(-1.5, null, "dashes", VolumeUnit.ML),
        )
        assertEquals("2500000000", UnitFormatter.formatOzFraction(2_500_000_000.0))
    }
}
