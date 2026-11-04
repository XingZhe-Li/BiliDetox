import com.github.xingzheli.bilidetox.hook.AdPanelSuppression

interface TestPanelListener {
    fun onPanelDismiss(shown: Boolean)
}

class PauseListener : TestPanelListener {
    var state = "visible"
    var dismissArgument: Boolean? = null
    override fun onPanelDismiss(shown: Boolean) {
        dismissArgument = shown
        state = "idle"
    }
}

class UnrelatedListener : TestPanelListener {
    var called = false
    override fun onPanelDismiss(shown: Boolean) { called = true }
}

class BrokenPauseListener : TestPanelListener {
    override fun onPanelDismiss(shown: Boolean) { error("host cleanup failed") }
}

fun main() {
    val dismiss = TestPanelListener::class.java.getMethod("onPanelDismiss", Boolean::class.javaPrimitiveType)
    for (type in listOf(3, 4)) {
        val pause = PauseListener()
        check(AdPanelSuppression.canSuppress(type, pause, PauseListener::class.java, dismiss))
        check(pause.state == "idle" && pause.dismissArgument == false)
    }
    val other = UnrelatedListener()
    check(!AdPanelSuppression.canSuppress(3, other, PauseListener::class.java, dismiss))
    check(!other.called)
    check(!AdPanelSuppression.canSuppress(3, null, PauseListener::class.java, dismiss))
    check(!AdPanelSuppression.canSuppress(4, PauseListener(), null, dismiss))
    check(!AdPanelSuppression.canSuppress(4, PauseListener(), PauseListener::class.java, null))
    check(!AdPanelSuppression.canSuppress(3, BrokenPauseListener(), BrokenPauseListener::class.java, dismiss))
    check(AdPanelSuppression.canSuppress(1, other, PauseListener::class.java, dismiss))
    check(!other.called)
    println("AdPanelSuppression: pause state cleanup, fail-open and unrelated callbacks passed")
}
