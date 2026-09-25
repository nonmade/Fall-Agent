package com.fall.shell.input

import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.fall.shell.core.Refl
import java.lang.reflect.Method

/**
 * 输入注入：反射 InputManager#injectInputEvent + InputEvent#setDisplayId，
 * 事件定向到目标 display（虚拟屏），不干扰主屏。
 */
class InputBridge {

    private val inputManager: Any? by lazy { resolveInputManager() }
    private val injectMethod: Method? by lazy {
        inputManager?.javaClass?.let { Refl.anyMethod(it, "injectInputEvent", 2) }
    }
    private val setDisplayIdMethod: Method? by lazy {
        Refl.anyMethod(InputEvent::class.java, "setDisplayId", 1)
    }

    private fun resolveInputManager(): Any? {
        val sm = Class.forName("android.os.ServiceManager")
        val get = Refl.anyMethod(sm, "getService", 1)
        val binder = Refl.invoke(null, get, "input")
            ?: return null
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        val asInterface = Refl.methodOf(stub, "asInterface", android.os.IBinder::class.java)
        return Refl.invoke(null, asInterface, binder)
    }

    fun tap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return send(displayId, motion(now, now, MotionEvent.ACTION_DOWN, x, y)) &&
            send(displayId, motion(now, now + 80, MotionEvent.ACTION_UP, x, y))
    }

    /** 长按：down → 持续 durationMs → up。 */
    fun longPress(displayId: Int, x: Int, y: Int, durationMs: Long = 600): Boolean {
        val now = SystemClock.uptimeMillis()
        if (!send(displayId, motion(now, now, MotionEvent.ACTION_DOWN, x, y))) return false
        Thread.sleep(durationMs.coerceIn(300, 3000))
        return send(displayId, motion(now, now + durationMs, MotionEvent.ACTION_UP, x, y))
    }

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 120): Boolean {
        val now = SystemClock.uptimeMillis()
        val steps = 12
        var ok = send(displayId, motion(now, now, MotionEvent.ACTION_DOWN, x1, y1))
        for (i in 1..steps) {
            val t = now + durationMs * i / steps
            val x = x1 + (x2 - x1) * i / steps
            val y = y1 + (y2 - y1) * i / steps
            ok = ok && send(displayId, motion(now, t, MotionEvent.ACTION_MOVE, x, y))
        }
        return ok && send(displayId, motion(now, now + durationMs, MotionEvent.ACTION_UP, x2, y2))
    }

    fun scroll(displayId: Int, direction: Int, distance: Int = 240): Boolean {
        return if (direction > 0) { // 向下滚（内容上移）
            swipe(displayId, 360, 800, 360, 800 - distance)
        } else {
            swipe(displayId, 360, 800, 360, 800 + distance)
        }
    }

    fun key(displayId: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0)
        return send(displayId, down) && send(displayId, up)
    }

    private fun send(displayId: Int, event: InputEvent): Boolean {
        runCatching { setDisplayIdMethod?.invoke(event, displayId) }
        val result = injectMethod?.invoke(inputManager, event, 1 /* WAIT_FOR_FINISH */)
        return result as? Boolean ?: false
    }

    private fun motion(now: Long, eventTime: Long, action: Int, x: Int, y: Int): MotionEvent =
        MotionEvent.obtain(now, eventTime, action, x.toFloat(), y.toFloat(), 0)

    companion object {
        const val BACK = KeyEvent.KEYCODE_BACK // 4
        const val HOME = KeyEvent.KEYCODE_HOME // 3
        const val ENTER = KeyEvent.KEYCODE_ENTER // 66
        const val SCROLL_DOWN = 1
        const val SCROLL_UP = -1
    }
}