fun box(): String {
    try {
        throw NullPointerException()
    } catch (e: IllegalArgumentException | NullPointerException) {
        return "OK"
    } finally {

    }

    return "fail"
}