package com.ryanvidal.barfriend.core.model

import kotlin.math.abs
import kotlin.math.roundToInt

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
        if (amount == null) return null
        val scaled = amount * scaleFactor
        val sourceUnit = VolumeUnit.fromApiKeyOrNull(fromUnit) ?: return scaled
        return convert(scaled, sourceUnit, targetUnit)
    }

    fun formatIngredientAmount(
        amount: Double?,
        amountMax: Double?,
        units: String?,
        targetUnit: VolumeUnit,
        scaleFactor: Double = 1.0,
    ): String {
        val sourceUnit = VolumeUnit.fromApiKeyOrNull(units)
        if (amount == null || amount == 0.0) {
            return units?.trim().orEmpty()
        }

        val scaledMin = amount * scaleFactor
        val scaledMax = amountMax?.takeIf { it > 0.0 }?.times(scaleFactor)

        if (sourceUnit == null) {
            val minText = formatDecimal(scaledMin)
            val maxText = scaledMax?.let { "-${formatDecimal(it)}" }.orEmpty()
            val unitSuffix = units?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
            return "$minText$maxText$unitSuffix"
        }

        val convertedMin = convert(scaledMin, sourceUnit, targetUnit)
        val convertedMax = scaledMax?.let { convert(it, sourceUnit, targetUnit) }

        val formattedMin = if (targetUnit == VolumeUnit.OZ) {
            formatOzFraction(convertedMin)
        } else {
            formatDecimal(convertedMin)
        }

        val formattedMax = convertedMax?.let { max ->
            val part = if (targetUnit == VolumeUnit.OZ) formatOzFraction(max) else formatDecimal(max)
            "-$part"
        }.orEmpty()

        return "$formattedMin$formattedMax ${targetUnit.label}"
    }

    fun formatOzFraction(oz: Double): String {
        if (oz <= 0.0) return "0"
        val whole = oz.toInt()
        val remainder = oz - whole
        val best = OZ_FRACTIONS.minByOrNull { abs(remainder - it.value) }
        if (best != null && abs(remainder - best.value) <= 0.04) {
            return when {
                best.value == 0.0 -> whole.toString()
                best.value == 1.0 -> (whole + 1).toString()
                whole == 0 -> best.glyph
                else -> "$whole${best.glyph}"
            }
        }
        return formatDecimal(oz)
    }

    private fun formatDecimal(value: Double): String {
        val rounded = (value * 100.0).roundToInt() / 100.0
        val asInt = rounded.toInt()
        if (abs(rounded - asInt) < 1e-6) {
            return asInt.toString()
        }
        val text = rounded.toString()
        return if (text.contains('.')) {
            text.trimEnd('0').trimEnd('.')
        } else {
            text
        }
    }

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
