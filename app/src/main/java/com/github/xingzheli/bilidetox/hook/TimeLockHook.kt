package com.github.xingzheli.bilidetox.hook

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.github.xingzheli.bilidetox.DailyTimeRange
import com.github.xingzheli.bilidetox.Log
import com.github.xingzheli.bilidetox.RuntimeConfig
import com.github.xingzheli.bilidetox.TimeLockPolicy
import com.github.xingzheli.bilidetox.ui.SettingsTheme
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap

/** 使用系统生命周期入口，覆盖冷启动、深链、后台返回和宿主的不同页面。 */
class TimeLockHook : BaseHook {
    override val name = "TimeLock"
    private val handler = Handler(Looper.getMainLooper())
    private class Screen(val activity: Activity) {
        var resumed = false
        var dialog: Dialog? = null
        var message: TextView? = null
        var audio: AudioManager? = null
        val focus = AudioManager.OnAudioFocusChangeListener { }
    }
    private val screens = IdentityHashMap<Activity, Screen>()
    // 只记录宿主自己的会话，不需要通知访问权限，也不影响其他应用的播放器。
    private val sessions = WeakHashMap<MediaSession, Boolean>()
    private val ticker = object : Runnable {
        override fun run() {
            screens.values.toList().forEach { screen -> safely { refresh(screen) } }
            safely {
                if (TimeLockPolicy.isBlocked(RuntimeConfig.get().timeLock, System.currentTimeMillis())) pausePlayback()
            }
            // 后台音频播放时也检查时段边界；暂停后没有可见页面则停止轮询。
            if (screens.isNotEmpty() || hasPlayingSessions()) handler.postDelayed(this, 1000)
        }
    }

    override fun hook() {
        XposedHelpers.findAndHookMethod(MediaSession::class.java, "setPlaybackState", PlaybackState::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                safely {
                    val session = param.thisObject as MediaSession
                    synchronized(sessions) { sessions[session] = true }
                    val state = param.args[0] as? PlaybackState
                    if (state?.state == PlaybackState.STATE_PLAYING) {
                        if (TimeLockPolicy.isBlocked(RuntimeConfig.get().timeLock, System.currentTimeMillis())) {
                            session.controller.transportControls.pause()
                        }
                        handler.post { schedule() }
                    }
                }
            }
        })
        lifecycle("callActivityOnStart") { activity ->
            screens.getOrPut(activity) { Screen(activity) }
            schedule()
        }
        lifecycle("callActivityOnResume") { activity ->
            val screen = screens.getOrPut(activity) { Screen(activity) }
            screen.resumed = true
            refresh(screen)
            schedule()
        }
        lifecycle("callActivityOnPause") { activity ->
            screens[activity]?.let { it.resumed = false; hide(it) }
        }
        lifecycle("callActivityOnStop") { activity -> remove(activity) }
        lifecycle("callActivityOnDestroy") { activity -> remove(activity) }
        // 被锁时不允许用画中画继续使用；已在画中画的页面由定时检查关闭。
        XposedBridge.hookAllMethods(Activity::class.java, "enterPictureInPictureMode", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (TimeLockPolicy.isBlocked(RuntimeConfig.get().timeLock, System.currentTimeMillis())) {
                    param.result = if ((param.method as java.lang.reflect.Method).returnType == Boolean::class.javaPrimitiveType) false else null
                }
            }
        })
        Log.d("已安装时段锁生命周期拦截")
    }

    private fun lifecycle(method: String, action: (Activity) -> Unit) {
        XposedHelpers.findAndHookMethod(Instrumentation::class.java, method, Activity::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                safely { action(param.args[0] as Activity) }
            }
        })
    }

    private fun schedule() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun remove(activity: Activity) {
        screens.remove(activity)?.let { hide(it) }
        if (screens.isEmpty() && !hasPlayingSessions()) handler.removeCallbacks(ticker)
    }

    private fun activeSessions(): List<MediaSession> = synchronized(sessions) { sessions.keys.toList() }

    private fun hasPlayingSessions(): Boolean = activeSessions().any {
        runCatching { it.controller.playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false)
    }

    private fun pausePlayback() {
        activeSessions().forEach { session ->
            safely {
                if (session.controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    session.controller.transportControls.pause()
                }
            }
        }
    }

    private fun refresh(screen: Screen) {
        val activity = screen.activity
        if (activity.isFinishing || activity.isDestroyed) {
            remove(activity)
            return
        }
        val config = RuntimeConfig.get().timeLock
        val now = System.currentTimeMillis()
        if (!TimeLockPolicy.isBlocked(config, now)) {
            hide(screen)
            return
        }
        pausePlayback()
        if (Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode) {
            activity.finish()
            return
        }
        if (!screen.resumed) return
        if (screen.dialog == null) show(screen)
        val until = TimeLockPolicy.nextAvailableAt(config, now)
        val name = if (config.periods == listOf(DailyTimeRange.NIGHT)) "夜间锁" else "时段锁"
        val format = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        screen.message?.text = "$name 生效中\n\n当前不能使用哔哩哔哩。\n请等到 ${format.format(Date(until))} 的可用时段再打开。\n\n" +
            "每日锁定时段：\n${config.periods.joinToString("\n") { it.label() }}" +
            if (config.isPledged(now)) "\n\n发誓锁定至 ${format.format(Date(config.pledgeUntil))}，到期前 BiliDetox 的所有设置均不可修改。" else ""
    }

    @Suppress("DEPRECATION")
    private fun show(screen: Screen) {
        val activity = screen.activity
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((28 * density).toInt(), (28 * density).toInt(), (28 * density).toInt(), (28 * density).toInt())
            setBackgroundColor(Color.rgb(24, 25, 29))
        }
        val text = TextView(activity).apply {
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        content.addView(ScrollView(activity).apply {
            isFillViewport = true
            addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(Button(activity).apply {
            SettingsTheme.styleButton(this, forceDark = true)
            this.text = "退出哔哩哔哩"
            setOnClickListener { activity.moveTaskToBack(true) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setContentView(content)
            setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) activity.moveTaskToBack(true)
                    true
                } else false
            }
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        screen.dialog = dialog
        screen.message = text
        // 对话框本身不会暂停视频；发送永久焦点丢失，退出锁定页后也不自动续播。
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.requestAudioFocus(screen.focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        screen.audio = audio
    }

    @Suppress("DEPRECATION")
    private fun hide(screen: Screen) {
        screen.dialog?.dismiss()
        screen.dialog = null
        screen.message = null
        screen.audio?.abandonAudioFocus(screen.focus)
        screen.audio = null
    }

    private fun safely(action: () -> Unit) {
        try { action() } catch (e: Throwable) { Log.e("时段锁检查失败", e) }
    }
}
