package com.ryanvidal.barfriend.core.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual class PlatformContext(val context: Context)

private class AndroidSecureTokenStore(
    private val context: Context,
) : SecureTokenStore {
    private val mutex = Mutex()
    private val tokenFile = File(context.noBackupFilesDir, "bar_friend_auth_token.enc")

    override suspend fun getToken(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!tokenFile.exists()) return@withLock null
            runCatching {
                val payload = tokenFile.readBytes()
                if (payload.size <= GCM_IV_LENGTH) return@runCatching null
                val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
                val encrypted = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(encrypted).decodeToString()
            }.getOrElse {
                tokenFile.delete()
                null
            }
        }
    }

    override suspend fun saveToken(token: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(token.encodeToByteArray())
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeBytes(iv + encrypted)
        }
    }

    override suspend fun clearToken() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (tokenFile.exists()) {
                tokenFile.delete()
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bar_friend_auth_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}

private class AndroidPlatformPaths(
    private val context: Context,
) : PlatformPaths {
    override fun databasePath(name: String): String =
        context.getDatabasePath(name).absolutePath

    override fun dataStorePath(name: String): String =
        File(context.filesDir, "datastore/$name").absolutePath

    override fun cachePath(name: String): String =
        File(context.cacheDir, name).absolutePath
}

actual fun createSecureTokenStore(context: PlatformContext): SecureTokenStore =
    AndroidSecureTokenStore(context.context.applicationContext)

actual fun createPlatformPaths(context: PlatformContext): PlatformPaths =
    AndroidPlatformPaths(context.context.applicationContext)

@Composable
actual fun KeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findActivity()?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (enabled) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
