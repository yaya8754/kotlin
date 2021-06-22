fun foo() {
    try {
        println()
        try {
            throw IllegalAccessError()
        } catch (f: IllegalStateException | IllegalArgumentException | NullPointerException) {
            f.printStackTrace()
        } finally {

        }
    } catch (e: IllegalArgumentException | NullPointerException) {
        println(e)
    }
}