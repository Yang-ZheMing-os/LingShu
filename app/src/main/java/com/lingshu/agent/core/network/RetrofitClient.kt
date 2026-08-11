package com.lingshu.agent.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.lingshu.agent.LingShuApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class NetworkState {
    AVAILABLE,
    UNAVAILABLE,
    LOST
}

@Singleton
class RetrofitClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val baseRetrofit: Retrofit
) {

    private val _networkState = MutableStateFlow(NetworkState.AVAILABLE)
    val networkState: StateFlow<NetworkState> = _networkState

    private var authToken: String? = null
    private var currentBaseUrl: String = "https://api.lingshu.example.com/"
    private val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("lingshu_device", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }

    private val appVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private val language: String by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0].language
        } else {
            context.resources.configuration.locale.language
        }
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    init {
        monitorNetwork()
    }

    private fun monitorNetwork() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _networkState.value = NetworkState.AVAILABLE
            }

            override fun onLost(network: Network) {
                _networkState.value = NetworkState.LOST
            }

            override fun onUnavailable() {
                _networkState.value = NetworkState.UNAVAILABLE
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        }
    }

    fun isNetworkAvailable(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun setAuthToken(token: String?) {
        authToken = token
    }

    fun getAuthToken(): String? = authToken

    fun setBaseUrl(url: String) {
        currentBaseUrl = url
    }

    fun getBaseUrl(): String = currentBaseUrl

    @JvmName("fetchDeviceId")
    fun getDeviceId(): String = deviceId

    fun getApiService(): ApiService {
        return baseRetrofit.newBuilder()
            .baseUrl(currentBaseUrl)
            .client(buildAuthenticatedClient())
            .build()
            .create(ApiService::class.java)
    }

    fun createCustomRetrofit(baseUrl: String): Retrofit {
        return baseRetrofit.newBuilder()
            .baseUrl(baseUrl)
            .client(buildAuthenticatedClient())
            .build()
    }

    private fun buildAuthenticatedClient(): OkHttpClient {
        return okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()

                authToken?.let {
                    builder.header("Authorization", "Bearer $it")
                }

                builder.header("X-Device-ID", deviceId)
                builder.header("X-App-Version", appVersion)
                builder.header("X-Platform", "android")
                builder.header("Accept-Language", language)

                chain.proceed(builder.build())
            }
            .build()
    }

    fun executeRequest(request: Request): okhttp3.Response {
        val authenticatedRequest = request.newBuilder().apply {
            authToken?.let { header("Authorization", "Bearer $it") }
            header("X-Device-ID", deviceId)
            header("X-App-Version", appVersion)
        }.build()

        return buildAuthenticatedClient().newCall(authenticatedRequest).execute()
    }
}
