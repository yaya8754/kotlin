typealias BF = Boolean | Float

fun foo() {
    var x = Any()

    if (x is <!UNION_TYPE_PROHIBITED_POSITION!>Int | String<!>) {}

    when (x) {
        !is <!UNION_TYPE_PROHIBITED_POSITION!>Int | String<!> -> TODO()
    }

    // there should also be a diagnostic here, will appear after modifications related to aliases
    x as BF
}
