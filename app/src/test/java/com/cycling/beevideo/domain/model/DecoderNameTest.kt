package com.cycling.beevideo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「这个名字是不是软件解码器」的判据。判错了设置页就会把硬解说成软解。 */
class DecoderNameTest {

    @Test
    fun `codec2 软件解码器`() {
        assertTrue(isSoftwareDecoder("c2.android.avc.decoder"))
        assertTrue(isSoftwareDecoder("c2.android.hevc.decoder"))
    }

    @Test
    fun `老 OMX 软件解码器`() {
        assertTrue(isSoftwareDecoder("OMX.google.h264.decoder"))
    }

    @Test
    fun `厂商前缀算硬解`() {
        assertFalse(isSoftwareDecoder("c2.qti.avc.decoder"))
        assertFalse(isSoftwareDecoder("c2.exynos.hevc.decoder"))
        assertFalse(isSoftwareDecoder("OMX.qcom.video.decoder.avc"))
        assertFalse(isSoftwareDecoder("OMX.MTK.VIDEO.DECODER.AVC"))
    }

    @Test
    fun `比对不分大小写`() {
        assertTrue(isSoftwareDecoder("C2.ANDROID.avc.decoder"))
        assertFalse(isSoftwareDecoder("C2.QTI.avc.decoder"))
    }

    @Test
    fun `软硬标记跟名字走`() {
        assertEquals(true, DecoderInUse("c2.android.avc.decoder").software)
        assertEquals(false, DecoderInUse("c2.qti.avc.decoder").software)
    }
}
