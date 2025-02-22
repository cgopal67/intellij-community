// "Move 'SomeOptInAnnotation' opt-in requirement from value parameter to property" "true"
// IGNORE_FIR
// COMPILER_ARGUMENTS: -opt-in=kotlin.RequiresOptIn
// WITH_STDLIB

@RequiresOptIn
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
annotation class SomeOptInAnnotation

class Foo(@SomeOptInAnnotation<caret> val value: Int) {
}
