package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.ContentSource

/**
 * 预览稿用的站点清单 —— 站点选择的预览要看的是**换行**，
 * 三个等长名字撑不出网格与横滑行的区别，所以这里要一份名字长短不齐的长清单。
 *
 * 名称是编的，不指向任何真实站点（本项目不分发、不推荐内容源）。
 */
object PreviewSites {

    /** 24 个：411dp 宽下大约 4–5 行。 */
    val manyNames: List<String> = listOf(
        "示例站",
        "备用一号",
        "聚合搜索",
        "动画专区",
        "CMS 采集",
        "本地测试站",
        "长名字的内容站点接口",
        "纪录片频道",
        "DemoJson",
        "采集库 B",
        "高清影视",
        "综艺专区",
        "短剧",
        "备用线路（慢）",
        "欧美剧集",
        "日韩专区",
        "老片仓库",
        "DemoXml",
        "自建接口",
        "网盘搜索",
        "体育回放",
        "儿童专区",
        "字幕组直连",
        "最后一个站点",
    )

    /** 上面那批名字对应的 [ContentSource]，`id` 用下标生成。 */
    val many: List<ContentSource> = manyNames.mapIndexed { index, name ->
        ContentSource(id = "demo_site_$index", name = name)
    }
}
