package io.github.bytejuno.theta.agent.plugin.calendar

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Binary authenticated encryption; operation binding is authenticated but not encrypted. */
internal interface CalendarEvidenceCodec {
    fun encrypt(plain: ByteArray, binding: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray, binding: ByteArray): ByteArray
}

/** Key supplier is a test seam; production keys never leave Android Keystore. */
internal class CalendarAesGcmEvidenceCodec(
    private val key: () -> SecretKey = ::calendarEvidenceKey,
) : CalendarEvidenceCodec {
    override fun encrypt(plain: ByteArray, binding: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(binding)
        check(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(encrypted: ByteArray, binding: ByteArray): ByteArray {
        require(encrypted.size >= 29 && encrypted[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted, 1, 12))
        cipher.updateAAD(binding)
        return cipher.doFinal(encrypted, 13, encrypted.size - 13)
    }
}

private val calendarKeyLock = Any()
private fun calendarEvidenceKey(): SecretKey = synchronized(calendarKeyLock) {
    val alias = "eta_calendar_operation_evidence_v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        generateKey()
    }
}
