typealias Test = Int? | () -> Unit

fun test() {
    val x: Int | String | Double | IntArray = 5

    val y: (a: Int | Boolean, b: Int | java.lang.String) -> Int | Float = TODO()

    fun foo(x: Boolean | (Int | Double, String) -> Int | Float): Throwable | Int {}

    fun bar<T, V>() {}

    bar<Int | String, Boolean>()
}