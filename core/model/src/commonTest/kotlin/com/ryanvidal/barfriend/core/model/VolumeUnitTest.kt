package com.ryanvidal.barfriend.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
