package com.cycling.beevideo.ui.home

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.SearchOutcome
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.model.VodPage
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.preview.PreviewVods
import java.io.IOException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页内容区的两次加载与它们之间那条**依赖**。
 *
 * 这些判据以前只写在注释里 —— 顺序（先分类、后内容）、以及三条回退规则
 * （分类没到 / 分类为空 / 分类加载失败时都按「推荐」）。它们错了也不报错，
 * 只是首页少东西或者选错分类，所以值得钉住。
 *
 * 用 `TestScope.backgroundScope` 提供作用域：`runTest` 结束时自动取消，
 * 不必替换 `Dispatchers.Main`。
 */
class HomeFeedStateTest {

    @Test
    fun `先拉分类，再按选中的分类拉内容`() = runTest {
        val content = RecordingContentRepository()
        val state = feedState(content)

        state.setSource(SOURCE)
        advanceTimeBy(1)

        assertEquals(
            "顺序不能反：内容请求的 tid 是从分类列表里挑出来的",
            listOf("categories", "listByCategory(${ContentRepository.CATEGORY_RECOMMEND})"),
            content.calls,
        )
    }

    @Test
    fun `切分类拉的是那个分类`() = runTest {
        val content = RecordingContentRepository()
        val state = feedState(content)
        state.setSource(SOURCE)
        advanceTimeBy(1)

        // PreviewVods.categories = [推荐, movie, tv, doc, anime]
        state.selectCategory(2)
        advanceTimeBy(1)

        assertEquals(2, state.selectedIndex)
        assertEquals("listByCategory(tv)", content.calls.last())
    }

    /** 分类还没到就先按「推荐」——那一个一定存在，不必等分类列表。 */
    @Test
    fun `分类为空时按推荐拉内容`() = runTest {
        val content = RecordingContentRepository(categoriesResult = { emptyList() })
        val state = feedState(content)

        state.setSource(SOURCE)
        advanceTimeBy(1)

        assertEquals(ContentRepository.CATEGORY_RECOMMEND, state.selectedCategoryId)
        assertEquals("listByCategory(${ContentRepository.CATEGORY_RECOMMEND})", content.calls.last())
    }

    /**
     * 分类挂了也要出内容：首页第一屏不该因为一次分类请求失败就变成整页错误。
     * 分类状态如实报 Failed，但内容照拉推荐。
     */
    @Test
    fun `分类加载失败时仍按推荐拉内容`() = runTest {
        val content = RecordingContentRepository(categoriesResult = { throw IOException("分类挂了") })
        val state = feedState(content)

        state.setSource(SOURCE)
        advanceTimeBy(1)

        assertTrue("分类状态要如实报失败：${state.categories.value}", state.categories.value is LoadState.Failed)
        assertEquals("listByCategory(${ContentRepository.CATEGORY_RECOMMEND})", content.calls.last())
    }

    @Test
    fun `内容加载失败进入 Failed 并带上原因`() = runTest {
        val content = RecordingContentRepository(vodsResult = { _, _ -> throw IOException("内容挂了") })
        val state = feedState(content)

        state.setSource(SOURCE)
        advanceTimeBy(1)

        val failed = state.vods.value
        assertTrue("应该是失败：$failed", failed is LoadState.Failed)
        assertEquals("内容挂了", (failed as LoadState.Failed).message)
    }

    /**
     * 持有者挂在 ViewModel 上、**不随来源变化重建**，所以换源必须显式重置。
     * 漏掉的话表现是"换了源，分类还是上一个源的" —— 而分类 id 在不同站点之间会撞车
     * （家家都有个 `tid=1`），所以这不只是"少点一次"。
     */
    @Test
    fun `换来源会重置选中分类并重新拉一遍`() = runTest {
        val content = RecordingContentRepository()
        val state = feedState(content)
        state.setSource(SOURCE)
        advanceTimeBy(1)
        state.selectCategory(3)
        advanceTimeBy(1)
        assertEquals(3, state.selectedIndex)

        state.setSource("another_source")
        advanceTimeBy(1)

        assertEquals("换源后选中项必须归零", 0, state.selectedIndex)
        assertEquals(
            "换源要**重新走一遍**：先是分类，再按归零后的选中项（推荐）拉内容",
            listOf("categories", "listByCategory(${ContentRepository.CATEGORY_RECOMMEND})"),
            content.calls.takeLast(2),
        )
    }

    /** 同一个来源重复 `setSource` 不该重复请求（重组会反复调它）。 */
    @Test
    fun `同一个来源重复 setSource 不重复请求`() = runTest {
        val content = RecordingContentRepository()
        val state = feedState(content)

        state.setSource(SOURCE)
        advanceTimeBy(1)
        state.setSource(SOURCE)
        advanceTimeBy(1)

        assertEquals(listOf("categories", "listByCategory(${ContentRepository.CATEGORY_RECOMMEND})"), content.calls)
    }

    // ------------------------------------------------------------------ 分页

    /** 触底追加：第 2 页**接在**原有列表后面，而不是把已经看到的内容换掉。 */
    @Test
    fun `追加下一页接在列表后面`() = runTest {
        val content = RecordingContentRepository(vodsResult = { _, page -> twoPages(page) })
        val state = feedState(content)
        state.setSource(SOURCE)
        advanceTimeBy(1)
        val firstPage = state.vods.items.size

        state.loadMore()
        advanceTimeBy(1)

        assertEquals(listOf(1, 2), content.pages)
        assertEquals("两页合起来就是全部条目", PreviewVods.vods.size, state.vods.items.size)
        assertTrue("第 2 页必须接在后面：$firstPage", state.vods.items.size > firstPage)
    }

    /** 源说了共 2 页，取完就该收手 —— 界面的触底事件会一直调 `loadMore`。 */
    @Test
    fun `到底之后不再请求下一页`() = runTest {
        val content = RecordingContentRepository(vodsResult = { _, page -> twoPages(page) })
        val state = feedState(content)
        state.setSource(SOURCE)
        advanceTimeBy(1)

        state.loadMore()
        advanceTimeBy(1)
        state.loadMore()
        advanceTimeBy(1)

        assertEquals(listOf(1, 2), content.pages)
        assertTrue("到底要如实上报，界面靠它显示「已显示全部」", !state.more.value.hasMore)
    }

    /**
     * 源没给 `pagecount` 时（老爬虫只吐 `{list:[…]}`），判据退化成"这一页有东西就继续"。
     * 所以源拿重复项充数时必须停得下来 —— 否则触底会无限空转。
     */
    @Test
    fun `源没给页数时重复的一页也算到底`() = runTest {
        val same = PreviewVods.vods
        val content = RecordingContentRepository(vodsResult = { _, _ -> VodPage(same, null) })
        val state = feedState(content)
        state.setSource(SOURCE)
        advanceTimeBy(1)

        state.loadMore()
        advanceTimeBy(1)

        assertEquals("内容不该变多", same.size, state.vods.items.size)
        assertTrue("重复页不许再往下拉", !state.more.value.hasMore)
    }

    /** 追加失败**不能动已经显示的内容**：多滑一屏失败就清空整页是最糟的表现。 */
    @Test
    fun `追加失败保留已加载的列表并留下重试入口`() = runTest {
        val content = RecordingContentRepository(
            vodsResult = { _, page ->
                if (page == 1) twoPages(1) else throw IOException("第二页挂了")
            },
        )
        val state = feedState(content)
        state.setSource(SOURCE)
        advanceTimeBy(1)
        val loaded = state.vods.items.size

        state.loadMore()
        advanceTimeBy(1)

        assertEquals("列表不该被失败清掉", loaded, state.vods.items.size)
        assertEquals("第二页挂了", state.more.value.error)
        assertTrue("失败后仍要留着重试的余地", state.more.value.hasMore)
    }

    /** 换分类等于重新开始：页码归零，上一分类的第 2 页不许追加到新分类下面。 */
    @Test
    fun `切分类会重置分页`() = runTest {
        val content = RecordingContentRepository(vodsResult = { _, page -> twoPages(page) })
        val state = feedState(content)
        state.setSource(SOURCE)
        advanceTimeBy(1)
        state.loadMore()
        advanceTimeBy(1)

        state.selectCategory(2)
        advanceTimeBy(1)

        assertEquals("新分类必须从第 1 页重新开始", listOf(1, 2, 1), content.pages)
    }

    // ------------------------------------------------------------------ 夹具

    private fun TestScope.feedState(content: ContentRepository) =
        HomeFeedState(content = content, scope = backgroundScope)

    private companion object {
        const val SOURCE = "mock_json"
    }
}

/** 首页那一栏当前显示的全部条目；未就绪时为空。 */
private val StateFlow<LoadState<List<Vod>>>.items: List<Vod>
    get() = (value as? LoadState.Ready)?.value.orEmpty()

/** 两页数据：前半 / 后半，源声明共 2 页。 */
private fun twoPages(page: Int): VodPage {
    val half = PreviewVods.vods.size / 2
    return VodPage(
        vods = if (page == 1) PreviewVods.vods.take(half) else PreviewVods.vods.drop(half),
        totalPages = 2,
    )
}

/**
 * 记录调用顺序的假仓储。
 *
 * 与 `ui/preview/FakeContentRepository` 分开：那个是**预览夹具**，不记调用；
 * 这个只为断言编排顺序存在，所以留在测试里。
 *
 * ⚠️ 参数名**必须**是 `categoriesResult` / `vodsResult`，不能叫 `categories` / `vods`：
 * 那样会和覆写的成员函数同名，而 `return categories()` 会被解析成**调用成员函数本身**
 * （而不是调用属性那个 lambda）→ 无限递归。而递归炸出来的 `StackOverflowError` 会被
 * `HomeFeedState.load` 的 `catch (e: Throwable)` 吞成"加载失败"，于是表症是
 * "分类列表为空、内容按推荐拉" —— 一条完全指不到这里的报错。
 */
private class RecordingContentRepository(
    private val categoriesResult: () -> List<Category> = { PreviewVods.categories },
    /** 按 (分类, 页) 给一页。默认只有一页 —— 分页用例自己传 [twoPages]。 */
    private val vodsResult: (String, Int) -> VodPage = { _, _ -> VodPage(PreviewVods.vods, 1) },
) : ContentRepository {

    val calls = mutableListOf<String>()

    /** 每次请求落在第几页。分页用例靠它断言"没有把同一页拉两遍"。 */
    val pages = mutableListOf<Int>()

    override suspend fun categories(): List<Category> {
        calls += "categories"
        return categoriesResult()
    }

    override suspend fun listByCategory(categoryId: String, page: Int): VodPage {
        calls += "listByCategory($categoryId)"
        pages += page
        return vodsResult(categoryId, page)
    }

    override suspend fun detail(vodId: String): Vod? = null

    override suspend fun search(keyword: String): SearchOutcome = SearchOutcome.EMPTY

    override suspend fun playTarget(
        vodId: String,
        lineName: String,
        episodeId: String,
    ): PlayTarget? = null
}
