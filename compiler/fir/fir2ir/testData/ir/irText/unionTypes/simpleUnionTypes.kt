// FIR_IDENTICAL
typealias Foo = Int | String

fun foo(a: (String | Boolean) -> Float | Int): Boolean | String = "foo"

fun bar() {
    try {

    } catch (e: IllegalArgumentException | NullPointerException) {
        e.printStackTrace()
    } finally {

    }
}