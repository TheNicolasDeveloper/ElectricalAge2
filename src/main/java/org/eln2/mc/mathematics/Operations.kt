package org.eln2.mc.mathematics

import kotlin.math.abs

fun Double.equals(other: Double, tolerance: Double = 10e-6): Boolean {
    return abs(this - other) < tolerance
}

infix fun Double.epsilonEquals(other: Double): Boolean {
    return this.equals(other)
}

fun Double.nanZero(): Double {
    if(this.isNaN()) {
        return 0.0
    }

    return this
}

fun Double.infinityZero(): Double {
    if(this.isInfinite()) {
        return 0.0
    }

    return this
}

fun Double.definedOrZero(): Double = this.nanZero().infinityZero()