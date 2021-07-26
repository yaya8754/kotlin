// !DIAGNOSTICS: -UNUSED_PARAMETER

open class Base<T>(pubg: Any?) {
    fun foo1(t: T) {}
}

class HD: Base<Int>(2) {
    in Hd class B : Base<Int> {
        constructor() : super(foo1(1))
        constructor(x: Int) : super(this@B.foo1(1))
        constructor(x: Int, y: Int) : super(this@HD.foo1(1))
    }
}
