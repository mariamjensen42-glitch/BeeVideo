package com.github.catvod.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * [Crypto] 的已知答案测试。
 *
 * ─── 为什么这些期望值值得写死 ─────────────────────────────────────────
 * `Crypto` 是 JS 爬虫做**接口签名**用的（`md5X` / `aesX` / `desX` / `rsaX`）。
 * 它错一位，表现是**接口返回 403 或空数据** —— 日志里什么都没有，
 * 只能靠"换一个源试试"来判断，真机上排查成本极高。
 *
 * 而它又是从参考实现逐行搬过来的，**搬运过程本身**（`android.util.Base64`
 * → `java.util.Base64`、Java 的 `switch` 表达式 → Kotlin 的 `when`、
 * `instanceof` 模式匹配 → `is`）每一处都可能悄悄改掉行为。
 * 所以这里用**独立实现**算出来的值钉住：
 *   - MD5 的期望值来自 .NET `MD5.HashData`；
 *   - AES-CBC 的期望值来自 .NET `Aes`（CBC + PKCS7）。
 * 不是"跑一遍我的实现、把输出抄成期望值"—— 那只能证明代码没被改动。
 */
class CryptoTest {

    @Test
    fun `md5 与独立实现一致`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Crypto.md5("abc"))
        assertEquals("5d41402abc4b2a76b9719d911017c592", Crypto.md5("hello"))
    }

    /**
     * 空输入返回**空串**，不是 `d41d8cd98f00b204e9800998ecf8427e`（空串的 MD5）。
     *
     * ⚠️ 这条**故意与"数学上正确"不同**：JS 侧用 `md5X(x) == ''` 判断
     * "没拿到内容"，返回真实摘要会让那个判据失效。
     */
    @Test
    fun `md5 空输入返回空串`() {
        assertEquals("", Crypto.md5(""))
        assertEquals("", Crypto.md5(null))
    }

    @Test
    fun `md5 文件与字符串一致`() {
        val file = File.createTempFile("crypto", ".txt").apply {
            writeText("hello")
            deleteOnExit()
        }
        assertEquals(Crypto.md5("hello"), Crypto.md5(file))
        assertTrue(Crypto.equals(file, Crypto.md5("hello")))
        assertEquals(false, Crypto.equals(file, "00000000000000000000000000000000"))
    }

    @Test
    fun `aes cbc 加密与独立实现一致`() {
        val out = Crypto.aes(
            mode = "AES/CBC",
            encrypt = true,
            input = "hello",
            inputBase64 = false,
            key = "1234567890123456",
            iv = "1234567890123456",
            outputBase64 = true,
        )
        // .NET: Aes(CBC, PKCS7), key/iv = "1234567890123456" 的 UTF-8 字节
        assertEquals("ObBxtb9plyPvM6ZEdBv6MQ==", out)
    }

    @Test
    fun `aes cbc 解密还原`() {
        val plain = Crypto.aes(
            mode = "AES/CBC",
            encrypt = false,
            input = "ObBxtb9plyPvM6ZEdBv6MQ==",
            inputBase64 = true,
            key = "1234567890123456",
            iv = "1234567890123456",
            outputBase64 = false,
        )
        assertEquals("hello", plain)
    }

    /**
     * 密钥短于 16 字节时**补零**而不是报错。
     *
     * 这是 JS 侧的既定语义：`aesX(… 'key', …)` 里给个短 key 是最常见的写法。
     * 补零之后 `"123"` 与 `"123" + 13 个 \u0000` 必须**等价**。
     */
    @Test
    fun `aes 短密钥补零`() {
        val shortKey = Crypto.aes("AES/CBC", true, "hello", false, "123", "1234567890123456", true)
        val paddedKey = Crypto.aes("AES/CBC", true, "hello", false, "123\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000", "1234567890123456", true)
        assertEquals(paddedKey, shortKey)
        assertTrue(shortKey.isNotEmpty())
    }

    /** ECB 没有 IV：`iv` 传空串时应当走"不用 IV"那条路，而不是抛。 */
    @Test
    fun `aes ecb 空 iv 可用`() {
        val out = Crypto.aes("AES/ECB", true, "hello", false, "1234567890123456", "", true)
        assertTrue("ECB 应当能算出结果，实际为空", out.isNotEmpty())
        val back = Crypto.aes("AES/ECB", false, out, true, "1234567890123456", "", false)
        assertEquals("hello", back)
    }

    /**
     * DES 走的是 **3DES（DESede）**，两把 16 字节密钥会被展开成 24 字节。
     * 写成单重 DES 会让所有 `desX` 调用算错 —— 这条只测"能自洽地加解密"，
     * 因为 3DES 的密钥展开方式（K1|K2|K1）是参考实现的行为规格，
     * 不是可以用独立实现随便对照的标准用法。
     */
    @Test
    fun `des 加解密自洽`() {
        val out = Crypto.des("DESede/CBC", true, "hello", false, "1234567890123456", "12345678", true)
        assertTrue("DES 应当能算出结果，实际为空", out.isNotEmpty())
        val back = Crypto.des("DESede/CBC", false, out, true, "1234567890123456", "12345678", false)
        assertEquals("hello", back)
    }

    /**
     * RSA 的**分块**逻辑：单次能加密的长度由模长减填充决定（2048 位是 245 字节），
     * 所以 600 字节必须走多块。
     *
     * 这是 `transformRsa` 存在的唯一理由，而它错了的表现是
     * "短文本能签、长文本签不出来" —— 只在特定长度的请求体上出现。
     */
    @Test
    fun `rsa pkcs1 长文本分块往返`() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = generator.generateKeyPair()
        val encoder = Base64.getEncoder()
        val publicKey = encoder.encodeToString(pair.public.encoded)
        val privateKey = encoder.encodeToString(pair.private.encoded)

        val long = "abcd".repeat(150) // 600 字节 > 245，必然分多块
        val encrypted = Crypto.rsa("RSA/PKCS1", true, true, long, false, publicKey, true)
        assertTrue("加密结果为空", encrypted.isNotEmpty())
        val decrypted = Crypto.rsa("RSA/PKCS1", false, false, encrypted, true, privateKey, false)
        assertEquals(long, decrypted)
    }

    /** PEM 头尾要能剥掉 —— JS 侧就是从 `.pem` 文件里整段复制过来的。 */
    @Test
    fun `rsa 接受带 PEM 头的公钥`() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = generator.generateKeyPair()
        val body = Base64.getEncoder().encodeToString(pair.public.encoded)
        val pem = "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"

        val encrypted = Crypto.rsa("RSA/PKCS1", true, true, "hi", false, pem, true)
        assertTrue("带 PEM 头的公钥应当可用，实际为空", encrypted.isNotEmpty())
    }

    /**
     * 失败一律**返回空串**，绝不抛 —— JS 侧靠"拿到空串"走签名失败分支。
     *
     * ⚠️ 这条对宿主很重要：`Crypto` 是被 `Global` 通过**反射**调进 JS 的，
     * 抛出去的异常会穿过 JNI 变成 JS 侧的一个 `Error`，而 JS 爬虫通常没有
     * try/catch 包住签名那几行 —— 结果是整个站点查询失败、日志里只有一个
     * 看不懂的 Java 异常。
     */
    @Test
    fun `参数不对时返回空串不抛`() {
        // 不认识的 AES 变换名 → Cipher.getInstance 抛 → 吞掉
        assertEquals("", Crypto.aes("不是密码模式", true, "x", false, "1234567890123456", "1234567890123456", true))
        // CBC 模式但 IV 为空 → newCipher 明确拒绝（AES 的 CBC 必须有 IV）
        assertEquals("", Crypto.aes("AES/CBC", true, "x", false, "1234567890123456", "", true))
        // 不认识的 RSA 模式名 → RsaMode.from 抛 → 吞掉
        assertEquals("", Crypto.rsa("RSA/不存在的模式", true, true, "x", false, "不是密钥", true))
        // 根本不是密钥的 RSA 输入 → KeyFactory 抛 → 吞掉
        assertEquals("", Crypto.rsa("RSA/PKCS1", true, true, "x", false, "不是密钥", true))
    }
}
