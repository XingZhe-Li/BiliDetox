import com.github.xingzheli.bilidetox.hook.BottomNavPolicy

fun main() {
    fun show(publish: Boolean, mall: Boolean, special: Boolean, uri: String?) =
        BottomNavPolicy.shouldShow(publish, mall, special, uri)

    check(show(true, true, true, "bilibili://uper/center_plus"))
    check(!show(false, true, true, "bilibili://uper/center_plus"))
    check(show(true, false, true, "bilibili://uper/center_plus"))
    for (uri in listOf("bilibili://mall/home", "bilibili://mall/homepage?from=tab", "BILIBILI://MALL/HOME/#x")) {
        check(!show(true, false, false, uri)) { uri }
        check(show(false, true, false, uri)) { uri }
    }
    for (uri in listOf(null, "bilibili://main/home", "bilibili://following/home", "bilibili://user_center", "bilibili://mall/home-extra")) {
        check(show(false, false, false, uri)) { "unrelated item must remain: $uri" }
    }
    println("BottomNavPolicy: independent switches, known routes and unknown item preservation passed")
}
