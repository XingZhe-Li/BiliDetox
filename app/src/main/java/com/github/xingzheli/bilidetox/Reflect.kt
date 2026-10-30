package com.github.xingzheli.bilidetox

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 反射小工具。
 *
 * 只保留本模块真正用到的能力，不做成通用框架。
 */

/** 查找类，失败返回 null 而不是抛异常。 */
fun String.findClassOrNull(classLoader: ClassLoader): Class<*>? = try {
    classLoader.loadClass(this)
} catch (_: ClassNotFoundException) {
    null
} catch (e: Throwable) {
    Log.e("加载类 $this 时出现异常", e)
    null
}

/**
 * 按名称与参数类型查找 declared 方法（含私有），失败返回 null。
 */
fun Class<*>.findMethodOrNull(name: String, vararg parameterTypes: Class<*>): Method? = try {
    getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
} catch (_: NoSuchMethodException) {
    null
} catch (e: Throwable) {
    Log.e("查找方法 ${this.name}#$name 时出现异常", e)
    null
}

/**
 * 按名称查找 declared 方法，忽略签名。
 *
 * 用于混淆后签名可能漂移、但同名方法唯一的场景。若同名方法多于一个则返回 null，
 * 避免 hook 错目标。
 */
fun Class<*>.findUniqueMethodByNameOrNull(name: String): Method? {
    val candidates = declaredMethods.filter { it.name == name }
    return when (candidates.size) {
        1 -> candidates.single().apply { isAccessible = true }
        0 -> null
        else -> {
            Log.w("方法 ${this.name}#$name 有 ${candidates.size} 个重载，拒绝按名称匹配")
            null
        }
    }
}

/** 读取字段值，失败返回 null。 */
fun Class<*>.findFieldOrNull(name: String): Field? = try {
    getDeclaredField(name).apply { isAccessible = true }
} catch (_: NoSuchFieldException) {
    null
} catch (e: Throwable) {
    Log.e("查找字段 ${this.name}#$name 时出现异常", e)
    null
}
