fun <T> foo(x: T | Int): T | Number = x

fun box() = foo<Boolean | String>("OK")