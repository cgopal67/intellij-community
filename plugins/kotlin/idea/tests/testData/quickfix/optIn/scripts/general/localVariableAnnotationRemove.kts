// "Remove annotation" "true"
// RUNTIME_WITH_SCRIPT_RUNTIME

@RequiresOptIn
@Target(AnnotationTarget.LOCAL_VARIABLE)
annotation class SomeOptInAnnotation

fun foo() {
    <caret>@SomeOptInAnnotation
    var x: Int = 0
}
