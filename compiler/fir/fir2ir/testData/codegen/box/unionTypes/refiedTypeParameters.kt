inline fun <reified E : Throwable> runCatchingErrors(f: () -> Unit): Unit? {
    try {
        return f()
    } catch (e: E) {
        return null
    }
}

fun runCatchingSomeErrors(f: () -> Unit) : Unit? {
    return runCatchingErrors<IllegalArgumentException | NullPointerException>(f)
}

fun box(): String {
    if (runCatchingSomeErrors { throw IllegalArgumentException() } != null)
        return "Fail 1"

    if (runCatchingSomeErrors { throw NullPointerException() } != null)
        return "Fail 2"

    try {
        if (runCatchingSomeErrors { throw RuntimeException() } == null)
            return "Fail 3"
    } catch (e: RuntimeException) {}

    return "OK"
}