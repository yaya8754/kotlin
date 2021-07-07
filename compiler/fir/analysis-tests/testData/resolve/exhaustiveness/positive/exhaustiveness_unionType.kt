fun test_1(x: Int | String | Boolean) {
    val y = when (x) {
        is Int -> 1
        is String -> 2
        is Boolean -> 3
    }
    val z = <!NO_ELSE_IN_WHEN!>when<!> (x) {
        is Int -> 1
        is String -> 2
    }
    val w = when (x) {
        !is Float -> 1
    }
}

fun test_2(x: Int? | Boolean) {
    val y = <!NO_ELSE_IN_WHEN!>when<!> (x) {
        is Int -> 1
        is Boolean -> 2
    }
    val z = when (x) {
        is Int -> 1
        is Boolean -> 2
        null -> 3
    }
    val w = when (x) {
        is Int -> 1
        is Boolean? -> 2
    }
}
