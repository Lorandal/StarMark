package dev.starmark.starmark.ui

import kotlin.math.abs
import kotlin.math.sign

/** No frame polling: the unconsumed vertical scroll is the actual boundary signal. */
internal class ScrollBoundaryFeedback(private val threshold: Float, private val rearmDistance: Float) {
    private var userMotion = false
    private var travelled = false
    private var edge = 0f
    private var away = 0f
    private var remainder = 0f

    fun scroll(consumed: Float, available: Float, userInput: Boolean): Boolean {
        if (userInput && (consumed != 0f || available != 0f)) userMotion = true
        if (!userMotion) return false
        if (abs(consumed) >= threshold) travelled = true
        if (edge != 0f && consumed.sign == -edge) {
            away += abs(consumed)
            if (away >= rearmDistance) { edge = 0f; away = 0f }
        } else if (consumed.sign == edge) away = 0f
        if (available == 0f) { remainder = 0f; return false }
        if (available.sign != remainder.sign) remainder = 0f
        remainder += available
        if (!travelled || abs(remainder) < threshold || available.sign == edge) return false
        edge = available.sign
        away = 0f
        remainder = 0f
        return true
    }

    fun finish() { userMotion = false; remainder = 0f }
}
