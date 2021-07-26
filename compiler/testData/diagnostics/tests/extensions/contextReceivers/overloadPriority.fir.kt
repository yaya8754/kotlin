class Context

<!CONFLICTING_OVERLOADS!>context(Context)
fun f()<!> {}

<!CONFLICTING_OVERLOADS!>fun f()<!> {}

fun test() {
    with(Context()) {
        f()
    }
}