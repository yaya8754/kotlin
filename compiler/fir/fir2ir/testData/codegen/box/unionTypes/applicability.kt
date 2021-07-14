fun foo(): Int | String = "OK"

fun bar(x: String | Number | Boolean) = x

fun box() = bar(foo())