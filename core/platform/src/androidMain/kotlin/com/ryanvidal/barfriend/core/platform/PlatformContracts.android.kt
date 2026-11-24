package com.ryanvidal.barfriend.core.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
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
            try {
                val payload = tokenFile.readBytes()
                if (payload.size <= GCM_IV_LENGTH) {
                    tokenFile.delete()
                    return@withLock null
                }
                val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
                val encrypted = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(encrypted).decodeToString()
            } catch (e: CancellationException) {
                throw e
            } catch (_: GeneralSecurityException) {
                tokenFile.delete()
                null
            } catch (_: IOException) {
                null
            } catch (_: ProviderException) {
                null
            }
        }
    }

    override suspend fun saveToken(token: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val payload = encryptWithRetry(token.encodeToByteArray())
            val parent = tokenFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val tempFile = File.createTempFile("bar_friend_auth_token_", ".tmp", parent)
            try {
                tempFile.writeBytes(payload)
                moveAtomicallyOrReplace(tempFile, tokenFile)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    private fun encryptWithRetry(plainBytes: ByteArray): ByteArray =
        try {
            encrypt(plainBytes)
        } catch (_: GeneralSecurityException) {
            deleteKeyStoreEntry()
            encrypt(plainBytes)
        }

    private fun encrypt(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainBytes)
        return iv + encrypted
    }

    private fun deleteKeyStoreEntry() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun moveAtomicallyOrReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
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
actual fun rememberLocalNetworkPermissionState(
    onResult: (granted: Boolean) -> Unit,
): LocalNetworkPermissionState {
    if (LocalInspectionMode.current || Build.VERSION.SDK_INT < 37) {
        return LocalNetworkPermissionState(
            isRequired = false,
            isGranted = true,
            requestPermission = {},
        )
    }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    var hasRequestedThisSession by rememberSaveable { mutableStateOf(false) }
    var isGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var isPermanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        isGranted = granted
        isPermanentlyDenied = if (granted) {
            false
        } else {
            hasRequestedThisSession &&
                activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        currentOnResult(granted)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                    PackageManager.PERMISSION_GRANTED
                isGranted = granted
                if (granted) {
                    isPermanentlyDenied = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return LocalNetworkPermissionState(
        isRequired = true,
        isGranted = isGranted,
        requestPermission = {
            hasRequestedThisSession = true
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        },
        isPermanentlyDenied = isPermanentlyDenied,
        openAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

@Composable
actual fun KeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val previous = view.keepScreenOn
        if (enabled) {
            view.keepScreenOn = true
        }
        onDispose {
            if (enabled) {
                view.keepScreenOn = previous
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

actual suspend fun hostResolvesToLocalNetwork(host: String): Boolean {
    val cleaned = host.trim().removePrefix("[").removeSuffix("]")
    if (cleaned.isEmpty()) return false
    return withTimeoutOrNull(2_000L) {
        withContext(Dispatchers.IO) {
            runCatching {
                InetAddress.getAllByName(cleaned).any { it.isLocalNetworkAddress() }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                false
            }
        }
    } ?: false
}

private fun InetAddress.isLocalNetworkAddress(): Boolean {
    if (isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress) {
        return true
    }
    val bytes = address
    return when (this) {
        is Inet4Address -> {
            if (bytes.size != 4) return false
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            first == 127 ||
                first == 10 ||
                (first == 192 && second == 168) ||
                (first == 172 && second in 16..31) ||
                (first == 169 && second == 254) ||
                (first == 100 && second in 64..127)
        }
        is Inet6Address -> {
            if (bytes.size != 16) return false
            val firstByte = bytes[0].toInt() and 0xFF
            val secondByte = bytes[1].toInt() and 0xFF
            (firstByte and 0xFE) == 0xFC ||
                (firstByte == 0xFE && (secondByte and 0xC0) == 0x80)
        }
        else -> false
    }
}
