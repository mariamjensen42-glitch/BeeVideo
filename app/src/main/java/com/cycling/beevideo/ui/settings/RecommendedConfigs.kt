package com.cycling.beevideo.ui.settings

import androidx.annotation.StringRes
import com.cycling.beevideo.R

/**
 * 设置页里的**预置配置地址**。
 *
 * ⚠️ 这一项**推翻了项目原来的红线**「不内置 / 不推荐 / 不分发内容源」——
 * 高城 2026-09-27 明确拍板（取舍与免责见 ADR-0008）。
 *
 * 每条都是**体检过的**（`probe_config.py`，结论见 `REFERENCE.md` 的
 * 「内容源 / 配置地址体检」），要点两条：
 *
 * 1. **地址必须带 `https://ghfast.top/` 镜像前缀**。配置里的 jar 写的是相对路径
 *    （`"spider": "./jar/fan.txt;md5;…"`），会跟着配置地址解析（`URI(base).resolve(ref)`）
 *    —— 少了前缀就落到 `cdn.jsdelivr.net`，而 jsdelivr 对 `*.jar` **全节点 403**
 *    （响应体 9 字节），结果**每个 `csp_` 源都报「下载 jar 失败」**。
 * 2. 只收**体检通过**的：jar 声明的 md5 与实际内容一致、`csp_` 类命中率高。
 *    ⚠️ `qist/dianshi.json` / `qist/jsm.json`（151 / 150 站、103 个 `csp_` 全命中，
 *    数字最漂亮）**不要加回来** —— 它们的 `spider.jar`（4.8MB dex）会让 App
 *    **直接 native 崩**（无 Java FATAL、无 ANR，真机必现）。
 */
internal data class RecommendedConfig(
    /** chip 上的名字，两三个字，要能一眼区分 */
    @param:StringRes val nameRes: Int,
    /** 选中后显示的一行说明（站点数 + 特点） */
    @param:StringRes val noteRes: Int,
    val url: String,
)

/**
 * 顺序就是推荐度。
 *
 * 排在第一位的是**真机端到端跑通过**的那份（`fty.json` + 糯米，首页 12 部 + 海报全出）。
 */
internal val RECOMMENDED_CONFIGS = listOf(
    RecommendedConfig(
        nameRes = R.string.settings_config_fty,
        noteRes = R.string.settings_config_fty_note,
        url = "https://ghfast.top/https://raw.githubusercontent.com/qist/tvbox/master/fty.json",
    ),
    RecommendedConfig(
        nameRes = R.string.settings_config_0825,
        noteRes = R.string.settings_config_0825_note,
        url = "https://ghfast.top/https://raw.githubusercontent.com/qist/tvbox/master/0825.json",
    ),
    RecommendedConfig(
        nameRes = R.string.settings_config_box,
        noteRes = R.string.settings_config_box_note,
        url = "https://ghfast.top/https://raw.githubusercontent.com/liu673cn/box/main/m.json",
    ),
    RecommendedConfig(
        nameRes = R.string.settings_config_9918,
        noteRes = R.string.settings_config_9918_note,
        url = "https://ghfast.top/https://raw.githubusercontent.com/qist/tvbox/master/9918.json",
    ),
    RecommendedConfig(
        nameRes = R.string.settings_config_gao,
        noteRes = R.string.settings_config_gao_note,
        url = "https://ghfast.top/https://raw.githubusercontent.com/gaotianliuyun/gao/master/0821.json",
    ),
)
