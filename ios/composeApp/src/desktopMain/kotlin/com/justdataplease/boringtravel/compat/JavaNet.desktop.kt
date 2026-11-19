package com.justdataplease.boringtravel.compat

// The desktop: Java's own exceptions (see commonMain's JavaNet.kt).

actual typealias SocketException = java.net.SocketException

actual typealias UnknownHostException = java.net.UnknownHostException

actual typealias ConnectException = java.net.ConnectException

actual typealias NoRouteToHostException = java.net.NoRouteToHostException

actual typealias InterruptedIOException = java.io.InterruptedIOException

actual typealias SocketTimeoutException = java.net.SocketTimeoutException

actual typealias SSLException = javax.net.ssl.SSLException

actual typealias SSLHandshakeException = javax.net.ssl.SSLHandshakeException

actual typealias GeneralSecurityException = java.security.GeneralSecurityException

actual typealias CertificateException = java.security.cert.CertificateException

actual typealias ProviderException = java.security.ProviderException

actual typealias AEADBadTagException = javax.crypto.AEADBadTagException
