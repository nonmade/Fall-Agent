package com.fall.shell.core

import java.lang.reflect.Field
import java.lang.reflect.Method

/** 隐藏 API 反射助手：全部异常返回 null，避免进程崩溃，交给调用方判空。 */
internal object Refl {

    fun fieldOf(clazz: Class<*>, name: String): Field? = runCatching {
        clazz.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()

    fun methodOf(clazz: Class<*>, name: String, vararg params: Class<*>): Method? =
        runCatching { clazz.getMethod(name, *params) }
            .getOrElse { runCatching { clazz.getDeclaredMethod(name, *params).apply { isAccessible = true } }.getOrNull() }

    fun anyMethod(clazz: Class<*>, name: String, paramCount: Int): Method? =
        clazz.methods.firstOrNull { it.name == name && it.parameterCount == paramCount }
            ?: clazz.declaredMethods.firstOrNull { it.name == name && it.parameterCount == paramCount }
                ?.apply { isAccessible = true }

    fun get(obj: Any?, field: Field?): Any? = runCatching { field?.get(obj) }.getOrNull()

    fun invoke(obj: Any?, method: Method?, vararg args: Any?): Any? =
        runCatching { method?.invoke(obj, *args) }.getOrNull()
}