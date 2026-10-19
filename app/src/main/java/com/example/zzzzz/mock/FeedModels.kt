package com.example.zzzzz.mock

import retrofit2.http.GET
import retrofit2.http.Query

data class FeedResponse(
    val page: Int,
    val size: Int,
    val total: Int,
    val hasMore: Boolean,
    val items: List<FeedItem>,
)

data class FeedItem(
    /** Khoá duy nhất của ô (dùng cho DiffUtil), khác imageId vì nhiều ô có thể trùng ảnh. */
    val id: String,
    val imageId: Int,
    val title: String,
    /** URL gốc KHÔNG có `?w=`. Loader tự chọn w ∈ {160,320,480,720,1080} theo cỡ ô. */
    val imageUrl: String,
)

interface FeedApi {
    @GET("v1/feed")
    suspend fun getFeed(
        @Query("page") page: Int,
        @Query("size") size: Int = 30,
    ): FeedResponse
}
