package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.utils.Prefers
import com.whl.quickjs.wrapper.JSMethod

/**
 * JS 全局对象上的 `local` —— 爬虫的**本地键值缓存**，对齐参考宿主的 `Local.java`。
 *
 * JS 侧：`local.set(rule, key, v)` / `local.get(rule, key)` / `local.delete(rule, key)`。
 *
 * ⚠️⚠️ 必须是 **class**，绝不能写成 `object`：宿主注入方式是
 * `ctx.getGlobalObject().setProperty("local", Local.class)`，而捆绑库会
 * `clazz.newInstance()` 先造实例、再按实例方法反射调用。Kotlin 的 `object` 是
 * 单例 + 私有构造器，`newInstance()` 抛 `IllegalAccessException` → 捆绑库抛
 * `NullPointerException: The JavaObj cannot be null`，整个 JS 上下文创建失败，
 * 而**这个错完全指不到"应该用 class"**。
 *
 * `@JSMethod` 是唯一的登记方式（运行时注解），漏标的在 JS 侧就是 `undefined`。
 */
class Local {

    /**
     * ⚠️ `rule` 为空串时**不产生连续两个下划线**：无条件拼 `"cache_${rule}_${key}"`
     * 会得到 `cache__key`，于是同一份缓存在"带 rule"和"不带 rule"下互相看不见。
     */
    private fun getKey(rule: String?, key: String?): String {
        val prefix = if (rule.isNullOrEmpty()) "" else rule + "_"
        return "cache_$prefix$key"
    }

    @JSMethod
    fun get(rule: String?, key: String?): String = Prefers.getString(getKey(rule, key))

    @JSMethod
    fun set(rule: String?, key: String?, value: String?) {
        Prefers.put(getKey(rule, key), value)
    }

    @JSMethod
    fun delete(rule: String?, key: String?) {
        Prefers.remove(getKey(rule, key))
    }
}
