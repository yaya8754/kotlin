object None

fun calc(f: () -> Unit): Unit | None {
    try {
        return f()
    } catch (e: Throwable) {
        return None
    }
}

fun box(): String {
    if (calc { 1 / 0 } is None)
        return "OK"

    return "Fail"
}