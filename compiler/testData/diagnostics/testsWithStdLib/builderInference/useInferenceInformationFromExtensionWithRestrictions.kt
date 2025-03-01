// !DIAGNOSTICS: -UNUSED_PARAMETER
// ALLOW_KOTLIN_PACKAGE

// FILE: annotation.kt

package kotlin

annotation class BuilderInference

// FILE: test.kt

class GenericController<T> {
    suspend fun yield(t: T) {}
}

suspend fun <S> GenericController<S>.extensionYield(s: S) {}

@BuilderInference
suspend fun <S> GenericController<S>.safeExtensionYield(s: S) {}

fun <S> generate(@BuilderInference g: suspend GenericController<S>.() -> Unit): List<S> = TODO()

val normal = generate {
    yield(42)
}

val extension = <!COULD_BE_INFERRED_ONLY_WITH_UNRESTRICTED_BUILDER_INFERENCE!>generate<!> {
    extensionYield("foo")
}

val safeExtension = generate {
    safeExtensionYield("foo")
}
