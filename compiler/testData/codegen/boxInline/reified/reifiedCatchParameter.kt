// TARGET_BACKEND: JVM_IR
// IGNORE_BACKEND: JVM_IR
// FILE: 1.kt
package test

inline fun <reified E : Throwable, R> runCatching(f: () -> R): R? {
    try {
        return f()
    } catch (e: E) {
        return null
    }
}

// FILE: 2.kt
import test.*

fun box() = when (runCatching<RuntimeException, Nothing> { throw NullPointerException() }) {
    null -> "OK"
    else -> "Fail"
}