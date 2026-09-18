package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.utils.Prefers
import com.whl.quickjs.wrapper.JSMethod

/**
 * JS 全局对象上的 `local` —— 爬虫的**本地键值缓存**
 * —— 逐行对齐参考宿主的 `quickjs/src/main/java/com/fongmi/quickjs/method/Local.java`。
 *
 * JS 侧用法：
 * ```js
 * local.set('rule名', 'token', 'xxx')   // 落盘，重启 App 还在
 * const t = local.get('rule名', 'token')
 * local.delete('rule名', 'token')
 * ```
 * 键会被拼成 `cache_<rule>_<key>`（rule 为空时是 `cache_<key>`），
 * 避免不同站点之间撞键 —— 大量 drpy 源共用同一份 JS 引擎实例。
 *
 * ─── ⚠️⚠️ 这里必须是 **class**，绝不能写成 `object` ──────────────────
 * 宿主把 `local` 注入 JS 的方式是：
 * ```java
 * ctx.getGlobalObject().setProperty("local", Local.class);
 * ```
 * 而捆绑库对 `setProperty(String, Class)` 的实现是（QuickJSObject.java）：
 * ```java
 * Object javaObj = clazz.newInstance();          // ← 需要 public 无参构造器
 * for (Method m : clazz.getMethods())
 *     if (m.isAnnotationPresent(JSMethod.class))
 *         jsObj.setProperty(m.getName(), args -> m.invoke(javaObj, args));
 * ```
 * 也就是说它**先 new 一个实例，再按"实例方法"反射调用**。
 *
 * Kotlin 的 `object Local` 编译出来是**单例 + 私有构造器**，
 * `newInstance()` 直接抛 `IllegalAccessException` → `javaObj == null` →
 * 捆绑库抛 `NullPointerException: The JavaObj cannot be null`，
 * 整个 JS 上下文创建失败。**这个错完全指不到"应该用 class"**。
 *
 * 所以：普通 `class`，构造器不写（Kotlin 自动给 public 无参构造器），
 * 方法都是 public 实例方法 + `@JSMethod`。
 *
 * `@JSMethod` 是**运行时**注解（`@Retention(RUNTIME)`），也是唯一的登记方式 ——
 * 漏标的方法在 JS 侧就是 `undefined`，报 `local.xxx is not a function`。
 */
class Local {

    /**
     * 拼缓存键。
     *
     * ⚠️ `rule` 为空串时**不产生连续两个下划线**：参考实现是
     * `"cache_" + (isEmpty(rule) ? "" : rule + "_") + key`，
     * 写成无条件 `"cache_${rule}_${key}"` 会得到 `cache__key`，
     * 于是同一份缓存在"带 rule"和"不带 rule"两种调用下**互相看不见**。
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
