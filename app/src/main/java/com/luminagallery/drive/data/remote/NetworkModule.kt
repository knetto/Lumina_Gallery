package com.luminagallery.drive.data.remote

import com.luminagallery.drive.BuildConfig
import com.luminagallery.drive.data.remote.auth.DriveAuthInterceptor
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Configures an HTTP/2-multiplexed, connection-pooled OkHttp 4 client shared across
 * Retrofit Drive metadata syncs, Coil 3 thumbnail/full-res streams, and Media3 ExoPlayer.
 */
object NetworkModule {

    fun createJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun createOkHttpClient(authInterceptor: DriveAuthInterceptor): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 12
        }

        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 2, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                }
            )
        }

        return builder.build()
    }

    fun createDriveApiService(okHttpClient: OkHttpClient, json: Json): DriveApiService {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(DriveConstants.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(DriveApiService::class.java)
    }
}
