package com.ryanvidal.barfriend.core.model

import kotlin.math.abs
import kotlin.math.roundToLong

enum class VolumeUnit(val apiKey: String, val label: String) {
    ML("ml", "ml"),
    OZ("oz", "oz"),
    CL("cl", "cl");

    companion object {
        fun fromApiKeyOrNull(value: String?): VolumeUnit? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.apiKey == normalized }
        }
    }
}

object UnitFormatter {
    private const val ML_PER_OZ = 30.0
    private const val ML_PER_CL = 10.0

    fun isConvertible(unit: String?): Boolean = VolumeUnit.fromApiKeyOrNull(unit) != null

    fun convert(amount: Double, from: VolumeUnit, to: VolumeUnit): Double {
        if (from == to) return amount
        val inMl = when (from) {
            VolumeUnit.ML -> amount
            VolumeUnit.OZ -> amount * ML_PER_OZ
            VolumeUnit.CL -> amount * ML_PER_CL
        }
        return when (to) {
            VolumeUnit.ML -> inMl
            VolumeUnit.OZ -> inMl / ML_PER_OZ
            VolumeUnit.CL -> inMl / ML_PER_CL
        }
    }

    fun scaleAmount(
        amount: Double?,
        fromUnit: String?,
        targetUnit: VolumeUnit,
        scaleFactor: Double = 1.0,
    ): Double? {
        if (amount == null || !amount.isFinite() || !scaleFactor.isFinite()) return null
        val scaled = amount * scaleFactor
        if (!scaled.isFinite()) return null
        val sourceUnit = VolumeUnit.fromApiKeyOrNull(fromUnit) ?: return scaled
        return convert(scaled, sourceUnit, targetUnit).takeIf { it.isFinite() }
    }

    fun formatIngredientAmount(
        amount: Double?,
        amountMax: Double?,
        units: String?,
        targetUnit: VolumeUnit,
        scaleFactor: Double = 1.0,
    ): String {
        val unitLabel = units?.trim().orEmpty()
        if (amount == null || !amount.isFinite() || !scaleFactor.isFinite()) {
            return unitLabel
        }
        val validMax = amountMax?.takeIf { it.isFinite() && it > amount }
        if (amount == 0.0 && (validMax == null || validMax <= 0.0)) {
            return unitLabel
        }

        val scaledMin = amount * scaleFactor
        val scaledMax = validMax?.times(scaleFactor)?.takeIf { it.isFinite() }
        if (!scaledMin.isFinite()) {
            return unitLabel
        }

        val sourceUnit = VolumeUnit.fromApiKeyOrNull(units)
        if (sourceUnit == null) {
            val minText = formatDecimal(scaledMin)
            val maxText = scaledMax?.let { formatDecimal(it) }?.takeIf { it != minText }?.let { "-$it" }.orEmpty()
            val unitSuffix = unitLabel.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
            return "$minText$maxText$unitSuffix"
        }

        val convertedMin = convert(scaledMin, sourceUnit, targetUnit)
        val convertedMax = scaledMax?.let { convert(it, sourceUnit, targetUnit) }
        if (!convertedMin.isFinite()) {
            return unitLabel
        }

        val formattedMin = formatConverted(convertedMin, targetUnit)
        val formattedMax = convertedMax
            ?.takeIf { it.isFinite() }
            ?.let { formatConverted(it, targetUnit) }
            ?.takeIf { it != formattedMin }
            ?.let { "-$it" }
            .orEmpty()

        return "$formattedMin$formattedMax ${targetUnit.label}"
    }

    private fun formatConverted(amount: Double, targetUnit: VolumeUnit): String =
        if (targetUnit == VolumeUnit.OZ) {
            formatOzFraction(amount)
        } else {
            formatDecimal(amount)
        }

    fun formatOzFraction(oz: Double): String {
        if (!oz.isFinite() || oz <= 0.0) return "0"
        if (oz > MAX_FRACTION_OZ) return formatDecimal(oz)
        val whole = oz.toLong()
        val remainder = oz - whole
        val best = OZ_FRACTIONS.minByOrNull { abs(remainder - it.value) }
        if (best != null && abs(remainder - best.value) <= 0.04) {
            when {
                best.value == 0.0 && whole > 0L -> return whole.toString()
                best.value == 0.0 -> Unit // Fall through to decimal formatting for small positive oz
                best.value == 1.0 -> return (whole + 1L).toString()
                whole == 0L -> return best.glyph
                else -> return "$whole${best.glyph}"
            }
        }
        val decimal = formatDecimal(oz)
        return if (decimal == "0") "<0.01" else decimal
    }

    private fun formatDecimal(value: Double): String {
        if (!value.isFinite()) return "0"
        val scaled = (abs(value) * 100.0).roundToLong()
        if (scaled == 0L) return "0"
        val sign = if (value < 0.0) "-" else ""
        val whole = scaled / 100L
        val frac = scaled % 100L
        return when {
            frac == 0L -> "$sign$whole"
            frac % 10L == 0L -> "$sign$whole.${frac / 10L}"
            frac < 10L -> "$sign$whole.0$frac"
            else -> "$sign$whole.$frac"
        }
    }

    private const val MAX_FRACTION_OZ = 1_000_000_000.0

    private data class OzFraction(val value: Double, val glyph: String)

    private val OZ_FRACTIONS = listOf(
        OzFraction(0.0, ""),
        OzFraction(1.0 / 8.0, "⅛"),
        OzFraction(1.0 / 4.0, "¼"),
        OzFraction(1.0 / 3.0, "⅓"),
        OzFraction(3.0 / 8.0, "⅜"),
        OzFraction(1.0 / 2.0, "½"),
        OzFraction(5.0 / 8.0, "⅝"),
        OzFraction(2.0 / 3.0, "⅔"),
        OzFraction(3.0 / 4.0, "¾"),
        OzFraction(7.0 / 8.0, "⅞"),
        OzFraction(1.0, ""),
    )
}
