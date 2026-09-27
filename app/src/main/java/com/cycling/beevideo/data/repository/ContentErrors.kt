package com.cycling.beevideo.data.repository

import com.cycling.beevideo.data.source.vod.catvod.CatVodException
import com.cycling.beevideo.domain.repository.ContentException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONException

/** 把异常翻译成**用户能读懂的一句话**。漏出去一个原始异常，界面就只能显示 java.net.ConnectException。 */
internal fun readable(e: Throwable): String = when (e) {
    is CatVodException, is ContentException -> e.message ?: "未知错误"
    // 顺序有讲究：这几个都是 IOException 的子类，放后面就被 IOException 吃掉了
    is UnknownHostException -> "无法解析地址，请检查网址和网络"
    is ConnectException -> "连接被拒绝，请确认地址可访问"
    is SocketTimeoutException -> "请求超时"
    is IOException -> "网络错误：${e.message.orEmpty()}"
    is JSONException -> "配置内容不是合法的 JSON"
    else -> e.message ?: e.javaClass.simpleName
}
