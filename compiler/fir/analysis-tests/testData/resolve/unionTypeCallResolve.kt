
interface I { fun foo() = 5 }
open class O(val value: Int)
class A : O(5), I
class B : O(6), I
class C : O(7)

fun bar() {
    val x : Int | String = TODO()
    x.hashCode()

    val y: A | B = TODO()
    y.value
    y.foo()
    y.<!UNRESOLVED_REFERENCE!>boo<!>()

    val z: A | B | C = TODO()
    z.value
    z.<!UNRESOLVED_REFERENCE!>foo<!>()
}
