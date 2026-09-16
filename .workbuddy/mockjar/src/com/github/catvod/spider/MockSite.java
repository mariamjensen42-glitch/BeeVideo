package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderApi;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;

/**
 * 本地 mock 的 spider —— **只用于验证 jar 加载链路**，不含任何真实内容源。
 *
 * 它的价值在于：**它是 Java 写的，且完全按真实 jar 的写法写**。
 * 宿主兼容层（{@link Spider} 那些类）是 Kotlin 写的，两边能不能对上
 * 只有把 Java 编译一遍、再到真机上跑一遍才知道 —— 光看 Kotlin 源码看不出来。
 *
 * 它逐条"证明"下面这些事（每一条都对应宿主踩过或差点踩到的坑）：
 *
 *   1. **继承 {@link Spider}**。能加载起来就说明 DexClassLoader 的父加载器
 *      委托链是对的 —— 这是整个 jar 方案最容易错的一步。
 *
 *   2. **静态调用 {@code Spider.client()} / {@code Spider.safeDns()} /
 *      {@code SpiderDebug.log(...)}**。这三个在真实 jar 里是 `invoke-static`，
 *      宿主只要写成 Kotlin 的实例方法就会 `NoSuchMethodError`
 *      （`client()` 真的这么错过一次）。探针结果直接显示在筛选行上。
 *
 *   3. **在 `init` 里读 {@link #siteKey}**。原版 `JarLoader.getSpider` 的顺序是
 *      "先赋 siteKey、再 init"，宿主顺序反了的话这里读到的是空串。
 *
 *   4. **覆盖两参的 {@code init(Context, String)}** —— 真实 jar（catvod 的
 *      `XPath` 等）覆写的就是这一个。收到的 ext 头 12 个字符会塞进分类名，
 *      **UI 上直接可见**，不用翻日志。
 *
 *   5. **覆盖 {@code homeVideoContent()}**。宿主必须调它，且返回非空时要覆盖
 *      首页推荐位（原版 `SiteApi.homeContent` 的行为）。这里固定返回一条
 *      "推荐位专用"，所以首页精选区出现它就说明合并成立。
 *
 *   6. **覆盖 {@code initApi(SpiderApi)} 并调 {@code super.initApi(api)}** ——
 *      实测真实 jar 里就是这个 `invoke-super` 写法。
 *
 *   7. **只覆盖两参的 {@code searchContent}**，故意不覆盖三参版。
 *      宿主按原版语义调两参版；三参版的基类默认实现返回空串（也是原版行为）。
 *
 * 它把 HTTP 请求打回同一个 mock 服务（`/api.php/provide/vod/`），也就是
 * "jar 里的代码 → 宿主网络层 → mock 服务 → 解析 → 返回给宿主"这条完整回路。
 */
public class MockSite extends Spider {

    /** 手机通过 `adb reverse tcp:18080 tcp:18080` 访问本机 mock 服务 */
    private static final String DEFAULT_BASE = "http://127.0.0.1:18080";

    private String base = DEFAULT_BASE;

    /** 宿主传来的 ext 原文，用于第 4 条那个可见证明 */
    private String extSeen = "";

    /** 在 init 里读到的 siteKey —— 验证宿主赋值**早于** init，见类注释第 3 条 */
    private String keyAtInit = "";

    /** 静态调用探针结果 */
    private String staticProbe = "未测";

    /** initApi 是否被宿主调用过 */
    private String apiSeen = "未调";

    /**
     * ⚠️ 签名照真实 jar 写：`init(Context, String)`。
     * 改成 `init(String)` 会让这个测试失去意义 —— 那正是宿主踩过的坑。
     */
    @Override
    public void init(Context context, String extend) {
        extSeen = extend == null ? "" : extend;
        String trimmed = extSeen.trim();
        if (trimmed.startsWith("http")) {
            base = trimmed;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        }
        keyAtInit = siteKey == null ? "(null)" : siteKey;
        staticProbe = probeStatics();
    }

    /**
     * 宿主能力注入。真实 jar 的写法就是覆写它、并把参数转交给父类。
     * 宿主目前传的是没有代理服务的空实现（见 `SpiderApi`），但**必须非 null**。
     */
    @Override
    public void initApi(SpiderApi api) {
        super.initApi(api);
        apiSeen = api == null ? "null" : "有";
    }

    /**
     * 探针：这三个都是真实 jar 里的 **静态调用**。
     * 少一个 `@JvmStatic`，这里就是 NoSuchMethodError —— 捕获后显示在界面上。
     */
    private String probeStatics() {
        try {
            Spider.client();
            Spider.safeDns();
            SpiderDebug.log("mock: 静态调用探针通过");
            return "ok";
        } catch (Throwable e) {
            return e.getClass().getSimpleName();
        }
    }

    @Override
    public String homeContent(boolean filter) {
        JSONObject out = new JSONObject();
        try {
            JSONObject classes = new JSONObject(OkHttp.string(vod("ac=list")));
            JSONObject list = new JSONObject(OkHttp.string(vod("ac=videolist")));
            out.put("class", withMarkers(classes.optJSONArray("class")));
            out.put("list", orEmptyArray(list.optJSONArray("list")));
        } catch (Exception e) {
            // mock 里不抛：抛出去只会变成宿主侧一条难查的反射错误
        }
        return out.toString();
    }

    /**
     * 首页**推荐位** —— 与 `homeContent` 是两次独立调用。
     *
     * 固定返回一条标题为"推荐位专用"的条目：宿主只要正确地"调了它、且非空时覆盖
     * 推荐位"，首页精选区就会出现这个名字。没有出现 = 宿主漏调或没合并。
     */
    @Override
    public String homeVideoContent() {
        JSONObject out = new JSONObject();
        try {
            JSONArray list = new JSONArray();
            JSONObject one = new JSONObject();
            one.put("vod_id", "hv-1");
            one.put("vod_name", "推荐位专用");
            one.put("vod_pic", "");
            list.put(one);
            out.put("list", list);
        } catch (Exception e) {
        }
        return out.toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            return shape(OkHttp.string(vod("ac=videolist&t=" + tid + "&pg=" + pg)));
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        JSONObject out = new JSONObject();
        try {
            String id = (ids == null || ids.isEmpty() || ids.get(0) == null) ? "" : ids.get(0);
            JSONObject root = new JSONObject(OkHttp.string(vod("ac=detail&ids=" + id)));
            JSONArray list = orEmptyArray(root.optJSONArray("list"));
            JSONArray only = new JSONArray();
            if (list.length() > 0) only.put(list.getJSONObject(0));
            out.put("list", only);
        } catch (Exception e) {
        }
        return out.toString();
    }

    /**
     * 只覆盖**两参**版本 —— 见类注释第 7 条。
     * 宿主按原版语义（page == "1"）调的就是这一个，所以能搜出结果。
     */
    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String encoded = URLEncoder.encode(key == null ? "" : key, "UTF-8");
            return shape(OkHttp.string(vod("ac=videolist&wd=" + encoded)));
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 播放地址。
     *
     * 传入的 id 本身就是 mock 数据里的 m3u8 地址，直接回给它 —— 这样这条链路
     * 验证的是"jar 能被调用、返回值能被解析"，而不是"能不能猜对某家站点的兑换规则"。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JSONObject out = new JSONObject();
        try {
            out.put("parse", 0);
            out.put("url", id == null ? "" : id);
            out.put("header", "");
        } catch (Exception e) {
        }
        return out.toString();
    }

    /**
     * 两条标记分类，插在最前面 —— 全部是"给肉眼看的证据"。
     *
     * **无条件发**：只有它出现了，才能拿标记内容去判断各条链路的状态。
     * 不发的话，"钩子没被调"和"标记被解析器丢了"这两种完全不同的故障
     * 会长得一模一样（这个坑真踩过）。
     */
    private JSONArray withMarkers(JSONArray original) {
        JSONArray cls = new JSONArray();
        try {
            JSONObject ext = new JSONObject();
            ext.put("type_id", "999");
            ext.put("type_name", extSeen.isEmpty() ? "ext=空" : extTag());
            cls.put(ext);

            JSONObject state = new JSONObject();
            state.put("type_id", "998");
            // key 必须在 init 时就已经拿到；static 必须是 ok；api 必须是"有"
            state.put("type_name", "key=" + keyAtInit + " 静态=" + staticProbe + " api=" + apiSeen);
            cls.put(state);
        } catch (Exception e) {
        }
        JSONArray src = orEmptyArray(original);
        for (int i = 0; i < src.length(); i++) {
            try {
                cls.put(src.get(i));
            } catch (Exception e) {
            }
        }
        return cls;
    }

    private String extTag() {
        String flat = extSeen.replace("\n", " ").replace("\r", " ").trim();
        String head = flat.length() > 12 ? flat.substring(0, 12) + "…" : flat;
        // 带上长度：空串和"传了个空对象"在界面上长得一样，看不到长度就分不出来
        return "ext[" + flat.length() + "]:" + head;
    }

    private String vod(String query) {
        return base + "/api.php/provide/vod/?" + query;
    }

    /** 列表响应统一成 CatVod 要求的形状：{list, page, pagecount} */
    private String shape(String body) {
        JSONObject out = new JSONObject();
        try {
            JSONObject root = new JSONObject(body);
            out.put("list", orEmptyArray(root.optJSONArray("list")));
            out.put("page", 1);
            out.put("pagecount", 1);
        } catch (Exception e) {
        }
        return out.toString();
    }

    private JSONArray orEmptyArray(JSONArray array) {
        return array == null ? new JSONArray() : array;
    }
}
