package com.ryanvidal.barfriend.feature.cocktails

import kotlin.math.abs
import kotlin.math.roundToInt

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

internal fun formatAbv(abv: Double): String {
    val tenths = (abv * 10.0).roundToInt()
    val whole = tenths / 10
    val remainder = abs(tenths % 10)
    return if (remainder == 0) {
        "$whole% ABV"
    } else {
        "$whole.$remainder% ABV"
    }
}

internal fun formatNumericInput(value: Double): String {
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
