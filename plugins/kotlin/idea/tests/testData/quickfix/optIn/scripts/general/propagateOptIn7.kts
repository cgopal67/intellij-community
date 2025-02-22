// "Propagate 'UnstableApi' opt-in requirement to 'SomeImplementation'" "true"
// RUNTIME_WITH_SCRIPT_RUNTIME

@RequiresOptIn
annotation class UnstableApi

@SubclassOptInRequired(UnstableApi::class)
interface CoreLibraryApi

fun interface SomeImplementation : CoreLibraryApi<caret> {
    fun method()
}