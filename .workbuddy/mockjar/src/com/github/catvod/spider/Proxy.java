package com.github.catvod.spider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * mock jar 自带的静态 `com.github.catvod.spider.Proxy`。
 *
 * ─── 它补上的是哪一块 ────────────────────────────────────────────────
 * 宿主侧「本地代理服务」这条链路（`LocalProxyServer` → `CatVodProxyDispatcher`
 * → 各 jar 的静态 Proxy）此前**一行覆盖都没有**：这个 jar 里没有 Proxy 类，
 * 于是 `DexJarLoader.invokeJarProxy` 每次都走 ClassNotFound 分支，
 * `proxyMethods` 永远是空的，派发器那条"挨个试静态 Proxy"的分支**取不到任何条目**
 * —— 代码写了，但从来没被真正执行过。
 *
 * 这个类一进来，那条分支就有了真实的执行者。
 *
 * ─── 它刻意做的事 ────────────────────────────────────────────────────
 * 1. **未知动作返回 null**。这是 CatVod 的既定协议（null = "这个请求我不管"），
 *    宿主必须据此判断"还要不要问下一个 jar"。返回 `{}` 之类的空数组会让宿主
 *    以为有人接手了。
 *
 * 2. **四种动作全部真实应答**（`m3u8` / `proxy` / `ck` / `media`）。
 *
 *    ⚠️ 这四个名字的**出处不一样**，注释里必须分清，否则下一次又会拿
 *    "我们编的名字"当成"真站的约定"去记欠账：
 *
 *    | 动作 | 出处 |
 *    |---|---|
 *    | `ck` | 真实。`custom_spider.jar` 与 `fty.jar` 的 dex 字符串表里有 `do=ck` 字面量 |
 *    | `m3u8` | 真实。真机上实测到过 `/proxy?do=m3u8&url=…` 并成功起播 |
 *    | `proxy` / `media` | **宿主自己推断的名字**。4 个真实 jar 的 dex 里全部 `do=` 取值是 `ali bili webdav local 6qc xbpq parseMix XYQBiu MixWeb ck`，**没有**这两个 |
 *
 *    那为什么还要实现这两个？因为**宿主侧**有对应的分支：`MediaMime.mimeTypeOfPlayUrl`
 *    对 `do=proxy` 和 `do=media` 各有一条判据。宿主声称支持的动作，
 *    mock 就必须能造出来，否则那两条判据在 L2 层永无执行者。
 *    但**只到这里为止** —— 不去改 m3u8 让分片真的走 `do=proxy`（见 `MockSite.playerContent`），
 *    那属于替一个没有出处的名字造证据。
 *
 *    并且**故意用两种字段顺序**：
 *      - `m3u8` / `proxy` / `ck` → `[状态码, MIME, 响应体, 响应头]`
 *      - `media`                → `[状态码, MIME, 响应头, 响应体]`  ← 换了个位置
 *    真实 jar 这两种都有，而宿主**只能按运行时类型判断谁是响应头**
 *    （见 `proxyPayloadOf`）。只造一种顺序就等于没测。
 *
 * 3. **响应体故意用三种不同类型**：`m3u8` / `media` 给 `InputStream`，
 *    `ck` 给 `String`。宿主三种都要能处理。
 *
 * 4. **`media` 返回 206**：`NanoHTTPD.Status` 的枚举里 206 是有的，但
 *    "jar 会返回非 200 的成功码"这件事得有一次真实发生。
 *
 * 5. **每个响应都带一个 `X-Mock-Proxy` 头**。取值就是动作名 —— 这样"到底是
 *    哪个 jar、走到了哪一支"在响应里直接可读，不用去翻日志猜。
 *
 * ⚠️ 这个类**不继承任何宿主类型**：真实 jar 的 Proxy 也是这样的普通静态类，
 * 宿主通过 `clazz.getMethod("proxy", Map.class)` 反射调用。
 * 它引用的 `com.github.catvod.Proxy`（拿来拼自指地址）由宿主在运行时提供，
 * 编译期作为 `--lib` 传进来、**不会被打进 jar**。
 */
public class Proxy {

    /**
     * 宿主调用的入口。
     *
     * @param params query 参数 + HTTP 头 + POST 表单合并后的那张表
     *               （CatVod 的既定契约，见 `LocalProxyServer.serve`）。
     * @return null 表示"我不处理这个请求"，宿主继续问下一个 jar。
     */
    public static Object[] proxy(Map<String, String> params) {
        if (params == null) return null;
        String action = params.get("do");
        if (action == null) return null;
        switch (action) {
            case "m3u8":
                return playlist(params);
            case "proxy":
                return segment(params);
            case "ck":
                return cookie(params);
            case "media":
                return media(params);
            default:
                // 「我不管这个请求」—— 宿主必须能区分这一条和"处理了但内容是空的"
                return null;
        }
    }

    /**
     * 主播放列表。分片地址指**回自己**（同一个 `/proxy`，动作换成 `proxy`），
     * 这样播放器拿到列表后会再打一次代理 —— 一条请求链上有两次派发，
     * 「一次播放只派发一次」这种错误假设会在这里露出来。
     *
     * 字段顺序：[状态码, MIME, 响应体, 响应头]
     */
    private static Object[] playlist(Map<String, String> params) {
        String base = com.github.catvod.Proxy.getUrl(true);
        String inner = params.get("url") == null ? "" : params.get("url");
        // inner 原样嵌回去：mock 用的地址里不含 & 和 #，不需要再编码。
        // 真实 jar 在这里要做一次 URL 编码 —— 这条链路的重点不是它。
        String body = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:4\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXTINF:4.0,\n"
                + base + "?do=proxy&url=" + inner + "\n"
                + "#EXT-X-ENDLIST\n";
        return new Object[]{200, "application/vnd.apple.mpegurl", stream(body), headers("m3u8")};
    }

    /**
     * 分片。真实分片是二进制，这里给一段 ASCII 标记就够 ——
     * 这条链路验证的是"请求被谁接走、响应怎么拼"，不是"分片能不能解码"。
     *
     * 状态码用 **206**：jar 返回非 200 的成功码是常态（拖动进度就是 206），
     * 宿主的状态码归一必须能接住。
     */
    private static Object[] segment(Map<String, String> params) {
        String body = "--MOCK-SEGMENT--" + params.get("url");
        return new Object[]{206, "video/mp2t", stream(body), headers("proxy")};
    }

    /**
     * `ck`（cookie 握手）。**响应体是 String，且只有 4 个元素、响应头是 null** ——
     * 覆盖"老版 jar 返回字符串"和"数组短一截"两种情况。
     */
    private static Object[] cookie(Map<String, String> params) {
        return new Object[]{200, "text/plain; charset=utf-8", "mock-ck", null};
    }

    /**
     * `media`。**字段顺序与其它三个相反**：响应头在第 3 位、响应体在第 4 位。
     *
     * 宿主 `proxyPayloadOf` 就是为这件事写的（第三位是 Map → 交换 2/3）。
     * 只造一种顺序，那段交换代码永远不会被执行到。
     */
    private static Object[] media(Map<String, String> params) {
        String body = "--MOCK-MEDIA--" + params.get("url");
        return new Object[]{200, "video/mp4", headers("media"), stream(body)};
    }

    private static Map<String, String> headers(String action) {
        Map<String, String> map = new HashMap<>();
        // 动作名直接写进响应头：这条响应是谁接的，看响应就知道，不用翻日志
        map.put("X-Mock-Proxy", action);
        // 一个真实站点会带的东西，顺带验证宿主确实把响应头透传给了播放器
        map.put("Access-Control-Allow-Origin", "*");
        return map;
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
