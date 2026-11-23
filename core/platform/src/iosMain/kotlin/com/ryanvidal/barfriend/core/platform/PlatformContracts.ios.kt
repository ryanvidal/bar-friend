@file:OptIn(ExperimentalForeignApi::class)

package com.ryanvidal.barfriend.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import platform.CoreFoundation.CFBridgingRelease
import platform.CoreFoundation.CFBridgingRetain
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIApplication

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

    override suspend fun saveToken(token: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            deleteTokenInternal()
            val nsData = (token as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return@withLock
            val attributes = CFDictionaryCreateMutable(
                null,
                5,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ) ?: return@withLock
            val serviceRef = CFBridgingRetain(SERVICE)
            val accountRef = CFBridgingRetain(ACCOUNT)
            val dataRef = CFBridgingRetain(nsData)
            try {
                CFDictionaryAddValue(attributes, kSecClass, kSecClassGenericPassword)
                CFDictionaryAddValue(attributes, kSecAttrService, serviceRef)
                CFDictionaryAddValue(attributes, kSecAttrAccount, accountRef)
                CFDictionaryAddValue(attributes, kSecValueData, dataRef)
                CFDictionaryAddValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
                SecItemAdd(attributes as CFDictionaryRef, null)
            } finally {
                releaseIfNotNull(serviceRef)
                releaseIfNotNull(accountRef)
                releaseIfNotNull(dataRef)
                CFRelease(attributes)
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
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        return requireNotNull(url?.path) + "/$name"
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
actual fun KeepScreenOnEffect(enabled: Boolean) {
    DisposableEffect(enabled) {
        if (enabled) {
            UIApplication.sharedApplication.idleTimerDisabled = true
        }
        onDispose {
            if (enabled) {
                UIApplication.sharedApplication.idleTimerDisabled = false
            }
        }
    }
}
