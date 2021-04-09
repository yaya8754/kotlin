// TARGET_BACKEND: JVM
// JVM_TARGET: 1.8
// SAM_CONVERSIONS: INDY

// FILE: protectedOuterClassConstructor.kt
interface GetStep {
    fun get(): Step
}

open class Outer {
    private val ok: String

    protected constructor(ok: String) {
        this.ok = ok
    }

    constructor() {
        this.ok = "xxx"
    }

    val obj = object : GetStep {
        override fun get(): Step = Step(::Outer)
    }

    override fun toString() = ok
}

fun box() =
    Outer().obj.get().step("OK").toString()


// FILE: Step.java
public interface Step {
    Object step(String string);
}
