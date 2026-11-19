package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.io.IOException

// iPhone: plain classes under Java's names (see commonMain's JavaNet.kt). No Apple API is called here.

actual open class SocketException actual constructor(message: String?) : IOException(message)

actual open class UnknownHostException actual constructor(message: String?) : IOException(message)

actual open class ConnectException actual constructor(message: String?) : SocketException(message)

actual open class NoRouteToHostException actual constructor(message: String?) : SocketException(message)

actual open class InterruptedIOException actual constructor(message: String?) : IOException(message)

actual open class SocketTimeoutException actual constructor(message: String?) : InterruptedIOException(message)

actual open class SSLException actual constructor(message: String?) : IOException(message)

actual open class SSLHandshakeException actual constructor(message: String?) : SSLException(message)

actual open class GeneralSecurityException actual constructor(message: String?) : Exception(message)

actual open class CertificateException actual constructor(message: String?) : GeneralSecurityException(message)

actual open class ProviderException actual constructor(message: String?) : RuntimeException(message)

actual open class AEADBadTagException actual constructor(message: String?) : GeneralSecurityException(message)
