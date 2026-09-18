package com.github.catvod.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.interfaces.RSAKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * 加解密工具 —— **本项目自带的兼容层**，逐行对齐参考宿主的
 * `catvod/src/main/java/com/github/catvod/utils/Crypto.java`（240 行）。
 *
 * ─── 谁在用 ──────────────────────────────────────────────────────────
 * JS 爬虫通过 `Global` 暴露的四个函数，全都是 **JS 侧主动调**：
 * ```
 * md5X(text)                                    消息摘要
 * aesX(mode, encrypt, input, inB64, key, iv, outB64)
 * desX(mode, encrypt, input, inB64, key, iv, outB64)
 * rsaX(mode, pub, encrypt, input, inB64, key, outB64)
 * ```
 * 真实 drpy 源里大量站点用它们做接口签名 —— 算法错一位，表现是**接口返回 403
 * 或空数据**，日志里什么都看不出来。所以这里是"要么完全对、要么完全不对"的一块。
 *
 * ─── 几个不是随手写的细节 ────────────────────────────────────────────
 * 1. **密钥/IV 用 `Arrays.copyOf` 补零到块长**（[padParameter]），**不**做哈希、
 *    **不**截断超长密钥。这是 JS 侧的既定语义：`aesX(… "key", …)` 里给个短 key
 *    是最常见的写法，补零之后 AES-128 直接用。
 * 2. **DES 用的是 `DESede`（3DES），不是 `DES`**。两把 16 字节密钥会被
 *    [getDesEdeKey] 展开成 24 字节（K1|K2|K1）—— 这正是 3DES 的两密钥模式。
 *    写成单重 DES 会让**所有** desX 调用算错。
 * 3. **RSA 要分块**（[transformRsa]）：RSA 单次能加密的长度由模长和填充决定，
 *    长文本必须按块切。而且 `NoPadding` 模式下**末块要左补零**
 *    （[transformRsaBlock]）—— 这是 JS 侧的约定，不是笔误。
 * 4. **失败一律返回空串**，不抛。JS 侧拿到空串会自己走"签名失败"分支，
 *    比抛异常穿到 JS 解释器里更好收场。
 *
 * ─── ⚠️ 与参考实现的差异：Base64 不用 `android.util.Base64` ────────────
 * 理由同 [Util]：`unitTests.isReturnDefaultValues = true` 会让 android.jar 的桩
 * **返回 null 而不是抛异常**，于是纯 JVM 单测里的加密结果静默变错。
 *
 * 参考实现的两处 flags 在这里的对应关系：
 *   - `Base64.decode(input.replace('_','/').replace('-','+'), Base64.DEFAULT)`
 *     → 先做同样的字母表归一，再走标准解码器（见 [decode]）；
 *   - `Base64.encodeToString(output, Base64.NO_WRAP)`
 *     → `Base64.getEncoder()`，本来就不换行。
 */
object Crypto {

    private const val MD5 = "MD5"
    private const val SHA_256 = "SHA-256"
    private const val BUFFER_SIZE = 64 * 1024
    private const val AES_BLOCK_SIZE = 16
    private const val DES_BLOCK_SIZE = 8
    private const val DES_EDE_TWO_KEY_SIZE = 16
    private const val DES_EDE_THREE_KEY_SIZE = 24

    private val HEX = "0123456789abcdef".toCharArray()

    private val OAEP_SHA1_PARAMETERS =
        OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    @JvmStatic
    fun md5(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return toHex(newDigest(MD5).digest(value.toByteArray(StandardCharsets.UTF_8)))
    }

    @JvmStatic
    fun md5(file: File): String = try {
        toHex(digest(file, newDigest(MD5)))
    } catch (_: IOException) {
        ""
    }

    @JvmStatic
    fun equals(file: File, expected: String?): Boolean =
        !expected.isNullOrEmpty() && expected.equals(md5(file), ignoreCase = true)

    @JvmStatic
    @Throws(IOException::class)
    fun sha256(file: File): ByteArray = digest(file, newDigest(SHA_256))

    @JvmStatic
    fun newDigest(algorithm: String): MessageDigest = try {
        MessageDigest.getInstance(algorithm)
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalStateException(e)
    }

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun decryptAesCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        newAesCipher("AES/CBC/PKCS5Padding", false, key, iv).doFinal(data)

    @JvmStatic
    fun aes(
        mode: String,
        encrypt: Boolean,
        input: String,
        inputBase64: Boolean,
        key: String,
        iv: String?,
        outputBase64: Boolean,
    ): String = try {
        val keyBytes = padParameter(key.toByteArray(StandardCharsets.UTF_8), AES_BLOCK_SIZE)
        val ivBytes = getIv(iv, AES_BLOCK_SIZE)
        val cipher = newAesCipher(getAesTransformation(mode), encrypt, keyBytes, ivBytes)
        encode(cipher.doFinal(decode(input, inputBase64)), outputBase64)
    } catch (_: Exception) {
        ""
    }

    @JvmStatic
    fun des(
        mode: String,
        encrypt: Boolean,
        input: String,
        inputBase64: Boolean,
        key: String,
        iv: String?,
        outputBase64: Boolean,
    ): String = try {
        val keyBytes = getDesEdeKey(key)
        val ivBytes = getIv(iv, DES_BLOCK_SIZE)
        val cipher = newCipher(getDesTransformation(mode), "DESede", encrypt, keyBytes, ivBytes)
        encode(cipher.doFinal(decode(input, inputBase64)), outputBase64)
    } catch (_: Exception) {
        ""
    }

    @JvmStatic
    fun rsa(
        mode: String,
        publicKey: Boolean,
        encrypt: Boolean,
        input: String,
        inputBase64: Boolean,
        key: String,
        outputBase64: Boolean,
    ): String = try {
        val rsaKey = generateRsaKey(publicKey, key)
        val rsaMode = RsaMode.from(mode)
        val cipher = newRsaCipher(rsaMode, encrypt, rsaKey)
        val output = transformRsa(cipher, rsaMode, encrypt, rsaKey, decode(input, inputBase64))
        encode(output, outputBase64)
    } catch (_: Exception) {
        ""
    }

    @Throws(IOException::class)
    private fun digest(file: File, digest: MessageDigest): ByteArray =
        FileInputStream(file).use { input: InputStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
            digest.digest()
        }

    @Throws(GeneralSecurityException::class)
    private fun newAesCipher(
        transformation: String,
        encrypt: Boolean,
        key: ByteArray,
        iv: ByteArray?,
    ): Cipher = newCipher(transformation, "AES", encrypt, key, iv)

    @Throws(GeneralSecurityException::class)
    private fun newCipher(
        transformation: String,
        algorithm: String,
        encrypt: Boolean,
        key: ByteArray,
        iv: ByteArray?,
    ): Cipher {
        if (iv == null && transformation.contains("/CBC/")) {
            throw GeneralSecurityException("IV is required for CBC mode")
        }
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(key, algorithm)
        val operation = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (iv == null) cipher.init(operation, keySpec)
        else cipher.init(operation, keySpec, IvParameterSpec(iv))
        return cipher
    }

    /** 空 IV → `null`（= 不用 IV 的 ECB 模式）；非空则补零到块长。 */
    private fun getIv(iv: String?, blockSize: Int): ByteArray? {
        if (iv.isNullOrEmpty()) return null
        return padParameter(iv.toByteArray(StandardCharsets.UTF_8), blockSize)
    }

    private fun getAesTransformation(mode: String): String = when {
        mode.startsWith("AES/CBC") -> "AES/CBC/PKCS5Padding"
        mode.startsWith("AES/ECB") -> "AES/ECB/PKCS5Padding"
        else -> mode + "Padding"
    }

    private fun getDesTransformation(mode: String): String =
        if (mode.startsWith("DESede/CBC")) "DESede/CBC/PKCS5Padding" else mode + "Padding"

    /**
     * 16 字节密钥展开成 24 字节（K1|K2|K1）—— 3DES 的两密钥模式。
     * 长度已经是 24（或别的值）时原样返回。
     */
    private fun getDesEdeKey(key: String): ByteArray {
        val bytes = padParameter(key.toByteArray(StandardCharsets.UTF_8), DES_EDE_TWO_KEY_SIZE)
        if (bytes.size != DES_EDE_TWO_KEY_SIZE) return bytes
        val expanded = Arrays.copyOf(bytes, DES_EDE_THREE_KEY_SIZE)
        System.arraycopy(bytes, 0, expanded, DES_EDE_TWO_KEY_SIZE, DES_BLOCK_SIZE)
        return expanded
    }

    private fun padParameter(value: ByteArray, minLength: Int): ByteArray =
        if (value.size < minLength) Arrays.copyOf(value, minLength) else value

    @Throws(GeneralSecurityException::class)
    private fun generateRsaKey(publicKey: Boolean, value: String): Key {
        val begin = if (publicKey) "-----BEGIN PUBLIC KEY-----" else "-----BEGIN PRIVATE KEY-----"
        val end = if (publicKey) "-----END PUBLIC KEY-----" else "-----END PRIVATE KEY-----"
        val key = value.replace("\r", "").replace("\n", "").replace(begin, "").replace(end, "")
        val factory = KeyFactory.getInstance("RSA")
        val bytes = decodeBase64(key)
        return if (publicKey) factory.generatePublic(X509EncodedKeySpec(bytes))
        else factory.generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    @Throws(GeneralSecurityException::class)
    private fun newRsaCipher(mode: RsaMode, encrypt: Boolean, key: Key): Cipher {
        val cipher = Cipher.getInstance(mode.transformation)
        val operation = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (mode == RsaMode.OAEP_SHA1) cipher.init(operation, key, OAEP_SHA1_PARAMETERS)
        else cipher.init(operation, key)
        return cipher
    }

    @Throws(GeneralSecurityException::class)
    private fun transformRsa(
        cipher: Cipher,
        mode: RsaMode,
        encrypt: Boolean,
        key: Key,
        input: ByteArray,
    ): ByteArray {
        if (input.isEmpty()) return input
        val rsaBlockSize = getRsaBlockSize(key)
        // 加密时每块要留出填充的字节数；解密时整块就是输入长度
        val inputBlockSize = if (encrypt) rsaBlockSize - mode.paddingOverhead else rsaBlockSize
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val length = minOf(inputBlockSize, input.size - offset)
            val block = transformRsaBlock(cipher, mode, input, offset, length, rsaBlockSize)
            output.write(block, 0, block.size)
            offset += inputBlockSize
        }
        return output.toByteArray()
    }

    @Throws(GeneralSecurityException::class)
    private fun transformRsaBlock(
        cipher: Cipher,
        mode: RsaMode,
        input: ByteArray,
        offset: Int,
        length: Int,
        blockSize: Int,
    ): ByteArray {
        // NoPadding 下"不满一块"必须是**左补零**（大整数是右对齐的）
        if (mode != RsaMode.NO_PADDING || length == blockSize) return cipher.doFinal(input, offset, length)
        val padded = ByteArray(blockSize)
        System.arraycopy(input, offset, padded, blockSize - length, length)
        return cipher.doFinal(padded)
    }

    @Throws(GeneralSecurityException::class)
    private fun getRsaBlockSize(key: Key): Int {
        if (key !is RSAKey) throw GeneralSecurityException("Invalid RSA key")
        return (key.modulus.bitLength() + 7) / 8
    }

    private fun decode(input: String, base64: Boolean): ByteArray =
        if (base64) decodeBase64(input.replace('_', '/').replace('-', '+'))
        else input.toByteArray(StandardCharsets.UTF_8)

    private fun encode(output: ByteArray, base64: Boolean): String =
        if (base64) Base64.getEncoder().encodeToString(output)
        else String(output, StandardCharsets.UTF_8)

    /**
     * PEM 正文解码。
     *
     * 比参考实现多做了两件事，都是"更宽容"的方向，且不改变合法输入的结果：
     * 剥掉残留空白（PEM 里本来就可能有）、补齐被省略的 `=` 填充。
     */
    private fun decodeBase64(value: String): ByteArray {
        val cleaned = value.filterNot { it.isWhitespace() }
        val remainder = cleaned.length % 4
        val padded = if (remainder == 0) cleaned else cleaned + "=".repeat(4 - remainder)
        return Base64.getDecoder().decode(padded)
    }

    private fun toHex(bytes: ByteArray): String {
        val result = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            result[index * 2] = HEX[value ushr 4]
            result[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(result)
    }

    private enum class RsaMode(val transformation: String, val paddingOverhead: Int) {
        PKCS1("RSA/ECB/PKCS1Padding", 11),
        NO_PADDING("RSA/ECB/NoPadding", 0),
        OAEP_SHA1("RSA/ECB/OAEPWithSHA-1AndMGF1Padding", 42),
        ;

        companion object {
            @Throws(GeneralSecurityException::class)
            fun from(mode: String): RsaMode = when (mode) {
                "RSA/PKCS1" -> PKCS1
                "RSA/None/NoPadding" -> NO_PADDING
                "RSA/None/OAEPPadding" -> OAEP_SHA1
                else -> throw GeneralSecurityException("Unsupported RSA mode: $mode")
            }
        }
    }
}
