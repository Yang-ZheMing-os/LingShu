package com.lingshu.agent.core.network

import com.lingshu.agent.core.model.HealthData
import com.lingshu.agent.core.model.ModInfo
import com.lingshu.agent.core.model.Script
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?
) {
    val isSuccess: Boolean get() = code == 200
}

data class PagedResponse<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int,
    val totalPages: Int
)

data class ChatMessageRequest(
    val role: String,
    val content: String,
    val images: List<String>? = null
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessageRequest>,
    val temperature: Float = 0.7f,
    val max_tokens: Int = 2048,
    val stream: Boolean = false
)

data class ChatResponseChoice(
    val message: ChatMessageRequest,
    val finish_reason: String
)

data class ChatResponse(
    val id: String,
    val object_type: String,
    val created: Long,
    val model: String,
    val choices: List<ChatResponseChoice>,
    val usage: TokenUsage?
)

data class TokenUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

interface ApiService {

    @POST("v1/chat/completions")
    suspend fun chat(@Body request: ChatRequest): Response<ApiResponse<ChatResponse>>

    @POST("v1/chat/completions")
    @Streaming
    suspend fun chatStream(@Body request: ChatRequest): ResponseBody

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody
    ): Response<ApiResponse<Map<String, String>>>

    @POST("v1/audio/speech")
    suspend fun synthesize(@Body request: Map<String, String>): Response<ResponseBody>

    @Multipart
    @POST("v1/vision/analyze")
    suspend fun analyzeImage(
        @Part image: MultipartBody.Part,
        @Part("prompt") prompt: RequestBody
    ): Response<ApiResponse<String>>

    @GET("v1/persona/store")
    suspend fun getPersonaStore(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("category") category: String? = null
    ): Response<ApiResponse<PagedResponse<com.lingshu.agent.core.model.Persona>>>

    @GET("v1/mods")
    suspend fun getModsList(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("category") category: String? = null,
        @Query("sort") sort: String = "popular"
    ): Response<ApiResponse<PagedResponse<ModInfo>>>

    @GET("v1/mods/{id}")
    suspend fun getModDetail(@Path("id") modId: String): Response<ApiResponse<ModInfo>>

    @GET("v1/mods/{id}/download")
    @Streaming
    suspend fun downloadMod(@Path("id") modId: String): Response<ResponseBody>

    @POST("v1/mods/{id}/rating")
    suspend fun rateMod(
        @Path("id") modId: String,
        @Body rating: Map<String, Float>
    ): Response<ApiResponse<Unit>>

    @POST("v1/auth/login")
    suspend fun login(@Body credentials: Map<String, String>): Response<ApiResponse<Map<String, String>>>

    @POST("v1/auth/register")
    suspend fun register(@Body userData: Map<String, String>): Response<ApiResponse<Map<String, String>>>

    @POST("v1/sync/upload")
    suspend fun uploadUserData(@Body data: RequestBody): Response<ApiResponse<Unit>>

    @GET("v1/sync/download")
    suspend fun downloadUserData(): Response<ApiResponse<ResponseBody>>

    @POST("v1/health/data")
    suspend fun uploadHealthData(@Body healthData: HealthData): Response<ApiResponse<Unit>>

    @GET("v1/health/data")
    suspend fun getHealthData(
        @Query("start_time") startTime: Long,
        @Query("end_time") endTime: Long,
        @Query("type") type: String? = null
    ): Response<ApiResponse<List<HealthData>>>

    @GET("v1/scripts/market")
    suspend fun getScriptMarket(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("category") category: String? = null
    ): Response<ApiResponse<PagedResponse<Script>>>

    @Multipart
    @POST("v1/upload")
    suspend fun uploadFile(@Part file: MultipartBody.Part): Response<ApiResponse<Map<String, String>>>

    @GET
    @Streaming
    suspend fun downloadFile(@retrofit2.http.Url url: String): Response<ResponseBody>

    @GET("{path}")
    suspend fun genericGet(@Path("path", encoded = true) path: String): Response<ApiResponse<ResponseBody>>

    @POST("{path}")
    suspend fun genericPost(
        @Path("path", encoded = true) path: String,
        @Body body: RequestBody
    ): Response<ApiResponse<ResponseBody>>
}
