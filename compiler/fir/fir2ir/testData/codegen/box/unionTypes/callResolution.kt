interface I {
    fun k() = "K"
}
open class O(val o: String)
class A : O("O"), I
class B : O(""), I

fun foo(): A | B = A()

fun box() = foo().o + "K" //TODO: foo().k() resolution