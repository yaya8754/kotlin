fun foo() {
    val x: Any | <!SUBTYPE_IN_UNION_TYPE!>Int<!> | <!SUBTYPE_IN_UNION_TYPE!>String<!> = 5

    try {

    } catch (e: RuntimeException | <!SUBTYPE_IN_UNION_TYPE!>NullPointerException<!>) {

    }

    val y: Boolean | <!FUNCTION_TYPE_IN_UNION_TYPE!>(Int) -> Boolean<!> = true
}
