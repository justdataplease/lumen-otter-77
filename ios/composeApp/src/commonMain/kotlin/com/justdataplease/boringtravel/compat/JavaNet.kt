package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.io.IOException

/*
 * The java.net, javax.net.ssl and java.security exceptions the Android app's AI files sort failures by (ai/own/
 * OwnKeyError.kt, OwnKeyStore.kt; ai/PlannerError.kt by name), under the same names. On the desktop they are Java's own
 * (desktopMain: typealiases), so what Java's HTTP client throws is sorted as on Android and Android's tests run
 * unchanged; on iPhone they are plain classes (iosMain), which [com.justdataplease.boringtravel.ios.NetException]
 * gives as its cause. Only what those files name is here.
 */

expect open class SocketException(message: String?) : IOException

expect open class UnknownHostException(message: String?) : IOException

expect open class ConnectException(message: String?) : SocketException

expect open class NoRouteToHostException(message: String?) : SocketException

expect open class InterruptedIOException(message: String?) : IOException

expect open class SocketTimeoutException(message: String?) : InterruptedIOException

expect open class SSLException(message: String?) : IOException

expect open class SSLHandshakeException(message: String?) : SSLException

expect open class GeneralSecurityException(message: String?) : Exception

expect open class CertificateException(message: String?) : GeneralSecurityException

expect open class ProviderException(message: String?) : RuntimeException

expect open class AEADBadTagException(message: String?) : GeneralSecurityException
