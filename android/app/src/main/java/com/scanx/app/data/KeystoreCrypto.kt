package com.scanx.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mã hoá chuỗi ngắn (API key) bằng khoá AES-256-GCM lưu trong Android Keystore — khoá gốc không bao
 * giờ rời phần cứng bảo mật của máy, kể cả lấy được file SharedPreferences cũng không đọc lại được
 * key gốc trên máy khác.
 *
 * KHÔNG dùng androidx.security-crypto (`EncryptedSharedPreferences`): thư viện này đã bị Google khai
 * tử từ bản 1.1.0-alpha07 (2025), chưa có API thay thế chính thức ổn định (bản kế nhiệm
 * `androidx.datastore:datastore-tink` vẫn alpha giữa 2026). Nên tự làm thẳng bằng API Keystore chuẩn
 * của Android SDK — không thêm dependency nào, không phụ thuộc thư viện đang trong vùng xám.
 *
 * Định dạng lưu: Base64(IV 12 byte || bản mã GCM). [decrypt] trả về chuỗi rỗng nếu giải mã lỗi (khoá
 * Keystore bị mất do gỡ cài đặt/đổi máy/khôi phục backup) thay vì crash — coi như người dùng chưa
 * nhập lại key, không văng lỗi.
 */
internal object KeystoreCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "scanx_secret_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_BYTES)
            val cipherText = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
