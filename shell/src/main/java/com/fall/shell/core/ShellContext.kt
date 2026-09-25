package com.fall.shell.core

import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.os.Process

/**
 * app_process 场景的系统 Context 构造（移植 ShadowAuto 方案）：
 * 自建 ActivityThread 实例 → 回填 sCurrentActivityThread / mSystemThread →
 * 补齐 mBoundApplication / mConfigurationController → 调实例 getSystemContext()。
 */
object ShellContext {

    const val PACKAGE_NAME = "com.android.shell"

    private val activityThreadClass: Class<*> = Class.forName("android.app.ActivityThread")

    private val activityThread: Any by lazy {
        // ActivityThread 构造会创建 Handler，要求主线程已准备 Looper（app_process 默认没有）
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }
        val ctor = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }
        val instance = try {
            ctor.newInstance()
        } catch (e: Exception) {
            println("[fall-shell] ActivityThread ctor failed: ${e.javaClass.simpleName} cause=${e.cause}")
            throw e
        }
        val current = activityThreadClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }
        current.set(null, instance)
        val systemThread = activityThreadClass.getDeclaredField("mSystemThread").apply { isAccessible = true }
        systemThread.setBoolean(instance, true)
        instance
    }

    fun create(): Context {
        val thread = activityThread // 触发初始化
        runCatching { fillApplicationInfo(thread) }
        runCatching { fillApplicationContext(thread) }
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { fillConfigurationController(thread) }
        }

        val m = runCatching { activityThreadClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true } }
            .getOrNull() ?: error("getSystemContext method not found")
        val ctx = m.invoke(thread) as? Context ?: error("getSystemContext returned null")
        return ShellAppContext(ctx)
    }

    /** mBoundApplication = AppBindData(appInfo=ApplicationInfo{pkg=com.android.shell}) */
    private fun fillApplicationInfo(thread: Any) {
        val bindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
        val bindData = bindDataClass.getDeclaredConstructor().newInstance()
        val appInfo = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
        }
        bindDataClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(bindData, appInfo)
        activityThreadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, bindData)
    }

    /** mInitialApplication = newApplication(Application, ctx) */
    private fun fillApplicationContext(thread: Any) {
        val ctx = contextRef()
        val app = Instrumentation.newApplication(Application::class.java, ctx)
        activityThreadClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
    }

    private fun contextRef(): Context {
        val m = runCatching { activityThreadClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true } }
            .getOrNull() ?: error("getSystemContext method not found")
        return m.invoke(activityThread) as Context
    }

    /** Android 12+：ConfigurationController(ActivityThreadInternal) 回填 */
    private fun fillConfigurationController(thread: Any) {
        val controllerClass = Class.forName("android.app.ConfigurationController")
        val internalClass = Class.forName("android.app.ActivityThreadInternal")
        val ctor = controllerClass.getDeclaredConstructor(internalClass).apply { isAccessible = true }
        val controller = ctor.newInstance(thread)
        activityThreadClass.getDeclaredField("mConfigurationController").apply { isAccessible = true }
            .set(thread, controller)
    }

    /** 包装为 com.android.shell 身份，满足 DisplayManager 等对 calling package/uid 的匹配校验。 */
    private class ShellAppContext(base: Context) : ContextWrapper(base) {
        private val attribution: AttributionSource? = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE_NAME).build()
            } else null
        }.getOrNull()

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = PACKAGE_NAME
        override fun getOpPackageName(): String = PACKAGE_NAME
        override fun getAttributionSource(): AttributionSource = attribution ?: super.getAttributionSource()

        override fun getSystemService(name: String): Any? {
            val service = super.getSystemService(name) ?: return null
            // 系统服务内部持有的 context 是 base（包名 android），会导致
            // "packageName must match the calling uid"；替换为当前包装以固化 shell 身份。
            if (service is android.hardware.display.DisplayManager ||
                name == Context.CLIPBOARD_SERVICE || name == Context.ACTIVITY_SERVICE
            ) {
                runCatching {
                    val f = service.javaClass.getDeclaredField("mContext").apply { isAccessible = true }
                    f.set(service, this)
                }.onFailure { e -> println("[fall-shell] service mContext patch: ${e.message}") }
            }
            return service
        }
    }
}