package com.ryanvidal.barfriend.feature.cocktails

import kotlin.math.abs
import kotlin.math.roundToLong

internal val EDITOR_MEASUREMENT_UNITS = listOf(
    "ml",
    "oz",
    "cl",
    "dash",
    "drop",
    "tsp",
    "tbsp",
    "barspoon",
    "part",
    "piece",
)

private const val MAX_FORMATTABLE_NUMERIC_ABS = 9e15
private const val MAX_FORMATTABLE_ABV_ABS = 9e17

internal fun formatAbv(abv: Double): String {
    if (!abv.isFinite() || abs(abv) > MAX_FORMATTABLE_ABV_ABS) return ""
    val tenths = (abv * 10.0).roundToLong()
    if (tenths == 0L) return "0% ABV"
    val sign = if (tenths < 0L) "-" else ""
    val absTenths = abs(tenths)
    val whole = absTenths / 10L
    val remainder = absTenths % 10L
    return if (remainder == 0L) {
        "$sign$whole% ABV"
    } else {
        "$sign$whole.$remainder% ABV"
    }
}

internal fun formatNumericInput(value: Double): String {
    if (!value.isFinite() || abs(value) > MAX_FORMATTABLE_NUMERIC_ABS) return ""
    val thousandths = (value * 1000.0).roundToLong()
    if (thousandths == 0L) return "0"
    val sign = if (thousandths < 0L) "-" else ""
    val absThousandths = abs(thousandths)
    val whole = absThousandths / 1000L
    val remainder = absThousandths % 1000L
    return if (remainder == 0L) {
        "$sign$whole"
    } else {
        val fraction = remainder.toString().padStart(3, '0').trimEnd('0')
        "$sign$whole.$fraction"
    }
}

private val STEP_PREFIX_REGEX = Regex("^(\\d+[.)]|[-•*])\\s+")

internal fun parseInstructionSteps(instructions: String): List<String> {
    return instructions
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line -> line.replaceFirst(STEP_PREFIX_REGEX, "").trim() }
        .filter { it.isNotEmpty() }
        .toList()
}
