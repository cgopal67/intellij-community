// "Remove annotation" "true"
// IGNORE_FIR
// COMPILER_ARGUMENTS: -opt-in=kotlin.RequiresOptIn
// WITH_STDLIB

@RequiresOptIn
@Target(AnnotationTarget.LOCAL_VARIABLE)
annotation class SomeOptInAnnotation

fun foo() {
    <caret>@SomeOptInAnnotation
    var x: Int = 0
}
