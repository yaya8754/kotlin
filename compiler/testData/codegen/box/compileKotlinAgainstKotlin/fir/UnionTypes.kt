// IGNORE_BACKEND: JVM, JVM_IR
// IGNORE_BACKEND_MULTI_MODULE: JVM_MULTI_MODULE_OLD_AGAINST_IR, JVM_MULTI_MODULE_IR_AGAINST_OLD
// MODULE: lib
// FILE: A.kt

fun foo(): Int | String = 5

// MODULE: main(lib)
// FILE: B.kt

fun box() = when (val x = foo()) {
    is Int -> "OK"
    is String -> "Fail"
}
