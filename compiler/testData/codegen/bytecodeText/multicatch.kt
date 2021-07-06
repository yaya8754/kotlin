// JVM_FIR

fun test() {
    try {
        try {
            throw IllegalStateException()
        } catch (f: IndexOutOfBoundsException | NullPointerException | IllegalStateException) {
            throw NullPointerException()
        }
    } catch (e: IllegalArgumentException | NullPointerException) {
        println()
    }
}

// 1 TRYCATCHBLOCK L0 L1 L1 java/lang/IndexOutOfBoundsException
// 1 TRYCATCHBLOCK L0 L1 L1 java/lang/NullPointerException
// 1 TRYCATCHBLOCK L0 L1 L1 java/lang/IllegalStateException
// 1 TRYCATCHBLOCK L2 L3 L3 java/lang/IllegalArgumentException
// 1 TRYCATCHBLOCK L2 L3 L3 java/lang/NullPointerException
// 1 LOCALVARIABLE f Ljava/lang/RuntimeException; L7 L3 1
// 1 LOCALVARIABLE e Ljava/lang/RuntimeException; L8 L11 0