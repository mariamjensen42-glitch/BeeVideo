package com.cycling.beevideo.data.proxy

/**
 * 本地代理服务的业务处理口。
 *
 * 把它和 HTTP 层分开：这一层只认「参数进、jar 约定的 `Object[]` 出」，
 * 完全不认识 HTTP —— 于是派发逻辑（找哪个 spider）可以单独推理和测试，
 * 而 HTTP 层不必知道 CatVod 的任何事。
 */
fun interface ProxyHandler {

    /**
     * @param params 请求的 query 参数 + HTTP 头 + POST 表单，**合并成一张表**。
     *   合并是 CatVod 的既定契约（参考实现 `session.getParms()` + `getHeaders()` +
     *   `files` 三处 putAll），不是我们图省事：jar 靠 `range` 这类头实现拖动进度，
     *   也靠 `do` 这类 query 参数区分动作，两者在同一个命名空间里取。
     * @return jar 约定的 `Object[]`；**null 表示没被任何 spider 接手**。
     */
    fun proxy(params: Map<String, String>): Array<Any?>?
}
