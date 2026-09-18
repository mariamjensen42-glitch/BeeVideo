package com.github.catvod.utils

/**
 * URI 解析 —— **本项目自带的兼容层**，逐行对齐参考宿主的
 * `catvod/src/main/java/com/github/catvod/utils/UriUtil.java`。
 *
 * ⚠️ 这段算法**不是随手写的**：它是把 `android.net.Uri.resolve(…)` 的语义
 * 按 RFC 3986 §5.2 抄下来的一份**纯 JVM 实现**（android.jar 的 `Uri` 在单测里是桩，
 * 而且它整套 API 都拖着一个 Android 依赖）。所以：
 *   - **不要"简化"它** —— 每条分支对应相对引用的一种形态（带 scheme、仅 fragment、
 *     仅 query、绝对路径、相对路径、base 无 authority…），删一条就有一类 URL 拼错；
 *   - 它没有副作用、不碰 Android API，因此**可单测**（见 `UriUtilTest`）。
 *
 * ─── 谁在用 ──────────────────────────────────────────────────────────
 * JS 爬虫的模块加载器：`BaseLoader` 语义的模块名归一化
 * （`JsEngine.createContext` → `moduleNormalizeName`），把 `import * as spider from '%s'`
 * 里的模块名按基准路径解析成绝对地址。**它错了的表现是模块加载失败**，
 * 而报错在 QuickJS 内部、只有一句 "could not load module"，指不到这里。
 *
 * ─── 与参考实现的差异：不依赖 `android.text.TextUtils` ────────────────
 * `TextUtils.isEmpty(uriString)` 换成 `uriString.isEmpty()`。语义相同
 * （这里传进来的已是非空 String），但单测里不会碰到 android.jar 的桩。
 */
object UriUtil {

    private const val INDEX_COUNT = 4
    private const val SCHEME_COLON = 0
    private const val PATH = 1
    private const val QUERY = 2
    private const val FRAGMENT = 3

    @JvmStatic
    fun resolve(baseUri: String?, referenceUri: String?): String {
        val uri = StringBuilder()
        val base = baseUri ?: ""
        val reference = referenceUri ?: ""
        val refIndices = getUriIndices(reference)
        // 引用自带 scheme（http: / https: / data: …）→ 它就是最终地址
        if (refIndices[SCHEME_COLON] != -1) {
            uri.append(reference)
            removeDotSegments(uri, refIndices[PATH], refIndices[QUERY])
            return uri.toString()
        }
        val baseIndices = getUriIndices(base)
        // 引用以 '#' 开头 → 只替换 fragment，其余原样取 base
        if (refIndices[FRAGMENT] == 0) {
            return uri.appendRange(base, 0, baseIndices[FRAGMENT]).append(reference).toString()
        }
        // 引用以 '?' 开头 → 只替换 query（含 fragment）
        if (refIndices[QUERY] == 0) {
            return uri.appendRange(base, 0, baseIndices[QUERY]).append(reference).toString()
        }
        // 引用是相对路径且 base 有 scheme → 保留 "scheme:"，后面整体接引用
        if (refIndices[PATH] != 0) {
            val baseLimit = baseIndices[SCHEME_COLON] + 1
            uri.appendRange(base, 0, baseLimit).append(reference)
            return removeDotSegments(uri, baseLimit + refIndices[PATH], baseLimit + refIndices[QUERY])
        }
        // 引用是绝对路径（以 '/' 开头）→ 只借 base 的 "scheme://authority"
        if (reference[refIndices[PATH]] == '/') {
            uri.appendRange(base, 0, baseIndices[PATH]).append(reference)
            return removeDotSegments(uri, baseIndices[PATH], baseIndices[PATH] + refIndices[QUERY])
        }
        /*
         * base 是 "scheme://" 这种**有 authority 但没有路径**的形态
         * （此时 PATH == QUERY，都指向 authority 结束处）→ 补一个 '/'。
         * 少了这条，"http://host" + "a.js" 会拼成 "http://hosta.js"。
         */
        if (baseIndices[SCHEME_COLON] + 2 < baseIndices[PATH] && baseIndices[PATH] == baseIndices[QUERY]) {
            uri.appendRange(base, 0, baseIndices[PATH]).append('/').append(reference)
            return removeDotSegments(uri, baseIndices[PATH], baseIndices[PATH] + refIndices[QUERY] + 1)
        }
        // 一般情况：砍掉 base 最后一段路径，接上引用
        val lastSlashIndex = base.lastIndexOf('/', baseIndices[QUERY] - 1)
        val baseLimit = if (lastSlashIndex == -1) baseIndices[PATH] else lastSlashIndex + 1
        uri.appendRange(base, 0, baseLimit).append(reference)
        return removeDotSegments(uri, baseIndices[PATH], baseLimit + refIndices[QUERY])
    }

    /**
     * 原地消掉 `.` 与 `..` 路径段（RFC 3986 §5.2.4）。
     *
     * ⚠️ 它**直接改 `uri`**，同时用局部 `limit` 跟着删减量走 ——
     * 把 `limit` 写成固定值是这个算法最容易犯的错，改完的结果"看起来对"，只是会在
     * 某些组合下少删或多删一段。
     */
    private fun removeDotSegments(uri: StringBuilder, offset: Int, limit: Int): String {
        var offset = offset
        var limit = limit
        if (offset >= limit) return uri.toString()
        // 开头的 '/' 不算路径段的一部分
        if (uri[offset] == '/') offset++
        var segmentStart = offset
        var i = offset
        while (i <= limit) {
            val nextSegmentStart: Int
            if (i == limit) {
                nextSegmentStart = i
            } else if (uri[i] == '/') {
                nextSegmentStart = i + 1
            } else {
                i++
                continue
            }
            if (i == segmentStart + 1 && uri[segmentStart] == '.') {
                // "." 段：删掉
                uri.delete(segmentStart, nextSegmentStart)
                limit -= nextSegmentStart - segmentStart
                i = segmentStart
            } else if (i == segmentStart + 2 && uri[segmentStart] == '.' && uri[segmentStart + 1] == '.') {
                // ".." 段：连同前一段一起删掉
                val prevSegmentStart = uri.lastIndexOf("/", segmentStart - 2) + 1
                val removeFrom = maxOf(prevSegmentStart, offset)
                uri.delete(removeFrom, nextSegmentStart)
                limit -= nextSegmentStart - removeFrom
                segmentStart = prevSegmentStart
                i = prevSegmentStart
            } else {
                i++
                segmentStart = i
            }
        }
        return uri.toString()
    }

    private fun getUriIndices(uriString: String): IntArray {
        val indices = IntArray(INDEX_COUNT)
        if (uriString.isEmpty()) {
            indices[SCHEME_COLON] = -1
            return indices
        }
        val length = uriString.length
        var fragmentIndex = uriString.indexOf('#')
        if (fragmentIndex == -1) fragmentIndex = length

        var queryIndex = uriString.indexOf('?')
        if (queryIndex == -1 || queryIndex > fragmentIndex) queryIndex = fragmentIndex

        // scheme 只能出现在第一个 '/' 或 '?' 之前
        var schemeIndexLimit = uriString.indexOf('/')
        if (schemeIndexLimit == -1 || schemeIndexLimit > queryIndex) schemeIndexLimit = queryIndex

        var schemeIndex = uriString.indexOf(':')
        if (schemeIndex > schemeIndexLimit) schemeIndex = -1

        // 有 authority 的判据是 "://"
        val hasAuthority = schemeIndex + 2 < queryIndex &&
            uriString.length > schemeIndex + 2 &&
            uriString[schemeIndex + 1] == '/' &&
            uriString[schemeIndex + 2] == '/'

        var pathIndex = if (hasAuthority) {
            uriString.indexOf('/', schemeIndex + 3)
        } else {
            schemeIndex + 1
        }
        if (hasAuthority && (pathIndex == -1 || pathIndex > queryIndex)) pathIndex = queryIndex

        indices[SCHEME_COLON] = schemeIndex
        indices[PATH] = pathIndex
        indices[QUERY] = queryIndex
        indices[FRAGMENT] = fragmentIndex
        return indices
    }
}
