@file:OptIn(ExperimentalForeignApi::class)

package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.ai.own.KeyValues
import com.justdataplease.boringtravel.ai.own.OwnAi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/*
 * Me › AI's keys and choices in the iPhone's Keychain (Android: its Keystore-sealed preferences file). Apple's APIs
 * called here: Security (SecItemCopyMatching, SecItemAdd, SecItemDelete; kSecClassGenericPassword items of one service,
 * kSecAttrAccessibleWhenUnlockedThisDeviceOnly: readable while the phone is unlocked, never moved to another device or
 * restored from a backup onto one), Core Foundation (CFDictionaryCreateMutable, CFDictionaryAddValue, CFRelease,
 * CFBridgingRetain and CFBridgingRelease) and Foundation (NSData, NSUserDefaults). Nothing here asks the traveller
 * anything.
 */

/**
 * [KeyValues] in the Keychain: each name ("mode", "key.claude" …) a generic password of [service]. Keychain items
 * outlive the app, and Android's preferences don't: on the first start after an install (no mark in the app's
 * UserDefaults yet) whatever an earlier install left is forgotten.
 */
class IosKeychain(private val service: String = SERVICE) : KeyValues {
    init {
        val defaults = NSUserDefaults.standardUserDefaults
        // One mark per service (Me › AI's kept its first name), so each forgets what an earlier install left.
        val mark = if (service == SERVICE) INSTALLED else "$INSTALLED.$service"
        if (!defaults.boolForKey(mark)) {
            clear()
            defaults.setBool(true, forKey = mark)
        }
    }

    override fun get(name: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = query(
            kSecClass to kSecClassGenericPassword, kSecAttrService to service, kSecAttrAccount to name,
            kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne,
        ) { SecItemCopyMatching(it, result.ptr) }
        if (status != errSecSuccess) return@memScoped null
        (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.decodeToString()
    }

    override fun put(name: String, value: String?) {
        query(kSecClass to kSecClassGenericPassword, kSecAttrService to service, kSecAttrAccount to name) { SecItemDelete(it) }
        if (value == null) return
        query(
            kSecClass to kSecClassGenericPassword, kSecAttrService to service, kSecAttrAccount to name,
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecValueData to value.encodeToByteArray().toNSData(),
        ) { SecItemAdd(it, null) }
    }

    override fun clear() {
        query(kSecClass to kSecClassGenericPassword, kSecAttrService to service) { SecItemDelete(it) }
    }

    /** A query of [items] for the length of [use]: Core Foundation's constants as they are, anything else bridged. */
    private fun <T> query(vararg items: Pair<CFStringRef?, Any?>, use: (CFDictionaryRef?) -> T): T {
        val bridged = ArrayList<CFTypeRef?>()
        val dict = CFDictionaryCreateMutable(null, items.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            for ((key, value) in items) {
                val cf: CFTypeRef? = if (value is CPointer<*>) value else CFBridgingRetain(value).also { bridged += it }
                CFDictionaryAddValue(dict, key, cf)
            }
            return use(dict)
        } finally {
            bridged.forEach { CFRelease(it) }
            CFRelease(dict)
        }
    }

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

    private fun NSData.toByteArray(): ByteArray {
        val out = ByteArray(length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }

    companion object {
        /** The Keychain service of Me › AI's items (Android's preferences file is "ai_own_key"). */
        const val SERVICE = "com.justdataplease.boringtravel." + OwnAi.PREFS

        /**
         * The account's items (round 23): Google's refresh token for Drive and the Firebase refresh token of a Sign in
         * with Apple account, this device only (Android keeps Google's grant in Play services, never in a backup).
         */
        const val ACCOUNT_SERVICE = "com.justdataplease.boringtravel.account"

        /** The app's UserDefaults mark that this install has seen its Keychain items. */
        private const val INSTALLED = "boringtravel.aiKeychainInstalled"
    }
}
