package com.luminagallery.drive.data.remote.auth

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp Interceptor that transparently injects the Service Account Bearer token
 * into Google Drive API calls, Coil thumbnail/full-res image fetches, and Media3
 * ExoPlayer video range requests. Automatically refreshes once on HTTP 401 and
 * short-circuits immediately when fast scrolling cancels an off-screen thumbnail call.
 */
class DriveAuthInterceptor(
    private val tokenManager: ServiceAccountTokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        if (chain.call().isCanceled()) {
            throw IOException("Request canceled before authentication.")
        }

        if (!isGoogleHost(originalRequest.url.host)) {
            return chain.proceed(originalRequest)
        }

        val token = tokenManager.getAccessToken(forceRefresh = false)
        if (chain.call().isCanceled()) {
            throw IOException("Request canceled during authentication.")
        }

        val authorizedRequest = originalRequest.newBuilder()
            .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$token")
            .build()

        val initialResponse = chain.proceed(authorizedRequest)

        if (initialResponse.code == 401 && !chain.call().isCanceled()) {
            initialResponse.close()
            val refreshedToken = tokenManager.getAccessToken(forceRefresh = true)
            val retriedRequest = originalRequest.newBuilder()
                .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$refreshedToken")
                .build()
            return chain.proceed(retriedRequest)
        }

        return initialResponse
    }

    private fun isGoogleHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "googleapis.com" ||
            lower.endsWith(".googleapis.com") ||
            lower == "googleusercontent.com" ||
            lower.endsWith(".googleusercontent.com") ||
            lower == "drive.google.com"
    }

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
