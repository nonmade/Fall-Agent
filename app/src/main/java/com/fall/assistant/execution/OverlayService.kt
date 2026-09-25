package com.fall.assistant.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.fall.core.agent.ExecutionChain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 执行可视化悬浮服务：Agent 执行期间在屏幕顶部显示状态胶囊，
 * 点击进入全屏监控页 [ExecutionActivity]。
 */
class OverlayService : Service() {

    private var capsule: TextView? = null
    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (Settings.canDrawOverlays(this)) {
            showCapsule()
            collectJob = scope.launch { ExecutionHub.state.collect(::render) }
        }
        return START_STICKY
    }

    private fun render(state: ExecutionUiState) {
        val view = capsule ?: return
        val prefix = if (state.chain == ExecutionChain.SILENT) "● 静默执行中" else "● AI 执行中"
        view.text = when {
            state.running -> "$prefix · ${state.steps.size} 步"
            state.error != null -> "⚠ 执行出错 · 点击查看"
            else -> "✓ 执行完成 · 点击查看"
        }
    }

    /** 灵动岛式横条：固定高度、圆角、状态图标 + 文本，点击按执行链分流。 */
    private fun showCapsule() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (capsule != null) return

        val v = TextView(this).apply {
            text = "● 静默执行中…"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(18), 0, dp(18), 0)
            height = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F24B3FE3"))
            }
            setOnClickListener {
                val chain = ExecutionHub.state.value.chain
                if (chain == ExecutionChain.SILENT) {
                    // 悬浮胶囊不收起：预览页在前台时再点 → 结束预览回到主屏；否则打开/带回预览页
                    if (!PreviewActivity.dismissIfForeground()) {
                        startActivity(
                            Intent(this@OverlayService, PreviewActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                                )
                            }
                        )
                    }
                } else {
                    val target = Intent(this@OverlayService, ExecutionActivity::class.java)
                    target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(target)
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(16)
        }
        runCatching { windowManager.addView(v, lp) }.onSuccess {
            capsule = v
        }.onFailure { stopSelf() }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        capsule?.let { runCatching { windowManager.removeView(it) } }
        capsule = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "fall_execution"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "AI 执行状态", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val launchPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, ExecutionActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Fall AI 执行中")
            .setContentText("点击查看操作进度")
            .setContentIntent(launchPi)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val NOTIFICATION_ID = 41001

        /**
         * 启动执行可视化：必须用 `startForegroundService` —— targetSdk 37 下在后台调
         * `startService` 会抛 `IllegalStateException` 且被调用处的 `runCatching` 静默吞掉，
         * 表现为"adb 下发任务没有胶囊"。前台服务在后台启动的豁免条件之一正是"已授予悬浮窗权限"，
         * 而本服务只有拿到该权限才能显示胶囊，因此该路径可用。
         */
        fun start(context: Context): Intent =
            Intent(context, OverlayService::class.java).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(this)
                } else {
                    context.startService(this)
                }
            }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}