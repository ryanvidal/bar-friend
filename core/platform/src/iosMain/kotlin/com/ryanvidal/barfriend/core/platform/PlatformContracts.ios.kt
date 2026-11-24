@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.ryanvidal.barfriend.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIApplication
import kotlin.native.concurrent.ThreadLocal

actual class PlatformContext

private class IosSecureTokenStore : SecureTokenStore {
    private val mutex = Mutex()

    override suspend fun getToken(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            memScoped {
                val query = CFDictionaryCreateMutable(
                    null,
                    5,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                ) ?: return@withLock null
                val serviceRef = CFBridgingRetain(SERVICE)
                val accountRef = CFBridgingRetain(ACCOUNT)
                try {
                    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
                    CFDictionaryAddValue(query, kSecAttrService, serviceRef)
                    CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
                    CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
                    CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

                    val resultRef = alloc<CFTypeRefVar>()
                    val status = SecItemCopyMatching(query as CFDictionaryRef, resultRef.ptr)
                    if (status != errSecSuccess) return@withLock null

                    val data = CFBridgingRelease(resultRef.value) as? NSData ?: return@withLock null
                    NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                } finally {
                    releaseIfNotNull(serviceRef)
                    releaseIfNotNull(accountRef)
                    CFRelease(query)
                }
            }
        }
    }

    override suspend fun saveToken(token: String): Unit {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val nsData = NSString.create(string = token).dataUsingEncoding(NSUTF8StringEncoding)
                    ?: throw IllegalStateException("Failed to encode token as UTF-8.")
                val serviceRef = CFBridgingRetain(SERVICE)
                val accountRef = CFBridgingRetain(ACCOUNT)
                val dataRef = CFBridgingRetain(nsData)
                val query = CFDictionaryCreateMutable(
                    null,
                    3,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                )
                val update = CFDictionaryCreateMutable(
                    null,
                    2,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                )
                try {
                    if (query == null || update == null) {
                        throw IllegalStateException("Failed to allocate Keychain dictionaries.")
                    }
                    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
                    CFDictionaryAddValue(query, kSecAttrService, serviceRef)
                    CFDictionaryAddValue(query, kSecAttrAccount, accountRef)

                    CFDictionaryAddValue(update, kSecValueData, dataRef)
                    CFDictionaryAddValue(
                        update,
                        kSecAttrAccessible,
                        kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                    )

                    var status = SecItemUpdate(query as CFDictionaryRef, update as CFDictionaryRef)
                    if (status == errSecItemNotFound) {
                        val attributes = CFDictionaryCreateMutable(
                            null,
                            5,
                            kCFTypeDictionaryKeyCallBacks.ptr,
                            kCFTypeDictionaryValueCallBacks.ptr,
                        ) ?: throw IllegalStateException("Failed to allocate Keychain attributes.")
                        try {
                            CFDictionaryAddValue(attributes, kSecClass, kSecClassGenericPassword)
                            CFDictionaryAddValue(attributes, kSecAttrService, serviceRef)
                            CFDictionaryAddValue(attributes, kSecAttrAccount, accountRef)
                            CFDictionaryAddValue(attributes, kSecValueData, dataRef)
                            CFDictionaryAddValue(
                                attributes,
                                kSecAttrAccessible,
                                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                            )
                            status = SecItemAdd(attributes as CFDictionaryRef, null)
                            if (status == errSecDuplicateItem) {
                                deleteTokenInternal()
                                status = SecItemAdd(attributes as CFDictionaryRef, null)
                            }
                        } finally {
                            CFRelease(attributes)
                        }
                    }
                    if (status != errSecSuccess) {
                        throw IllegalStateException("Failed to persist token in Keychain: OSStatus $status")
                    }
                } finally {
                    releaseIfNotNull(serviceRef)
                    releaseIfNotNull(accountRef)
                    releaseIfNotNull(dataRef)
                    if (query != null) CFRelease(query)
                    if (update != null) CFRelease(update)
                }
            }
        }
    }

    override suspend fun clearToken() = withContext(Dispatchers.IO) {
        mutex.withLock {
            deleteTokenInternal()
        }
    }

    private fun deleteTokenInternal() {
        val query = CFDictionaryCreateMutable(
            null,
            3,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: return
        val serviceRef = CFBridgingRetain(SERVICE)
        val accountRef = CFBridgingRetain(ACCOUNT)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, serviceRef)
            CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
            SecItemDelete(query as CFDictionaryRef)
        } finally {
            releaseIfNotNull(serviceRef)
            releaseIfNotNull(accountRef)
            CFRelease(query)
        }
    }

    private fun releaseIfNotNull(ref: CFTypeRef?) {
        if (ref != null) {
            CFRelease(ref)
        }
    }

    private companion object {
        const val SERVICE = "com.ryanvidal.barfriend.auth"
        const val ACCOUNT = "sanctum_bearer_token"
    }
}

private class IosPlatformPaths : PlatformPaths {
    override fun databasePath(name: String): String {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        val basePath = requireNotNull(url?.path)
        val dbDir = "$basePath/Database"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = dbDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        NSURL.fileURLWithPath(dbDir, isDirectory = true)
            .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        return "$dbDir/$name"
    }

    override fun dataStorePath(name: String): String {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        return requireNotNull(url?.path) + "/$name"
    }

    override fun cachePath(name: String): String {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        return requireNotNull(url?.path) + "/$name"
    }
}

actual fun createSecureTokenStore(context: PlatformContext): SecureTokenStore =
    IosSecureTokenStore()

actual fun createPlatformPaths(context: PlatformContext): PlatformPaths =
    IosPlatformPaths()

@Composable
actual fun rememberLocalNetworkPermissionState(
    onResult: (granted: Boolean) -> Unit,
): LocalNetworkPermissionState = LocalNetworkPermissionState(
    isRequired = false,
    isGranted = true,
    requestPermission = {},
)

@ThreadLocal
private var keepScreenOnCount = 0

@Composable
actual fun KeepScreenOnEffect(enabled: Boolean) {
    DisposableEffect(enabled) {
        if (enabled) {
            keepScreenOnCount += 1
            UIApplication.sharedApplication.idleTimerDisabled = true
        }
        onDispose {
            if (enabled) {
                keepScreenOnCount = (keepScreenOnCount - 1).coerceAtLeast(0)
                if (keepScreenOnCount == 0) {
                    UIApplication.sharedApplication.idleTimerDisabled = false
                }
            }
        }
    }
}
