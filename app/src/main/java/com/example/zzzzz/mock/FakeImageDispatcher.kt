package com.example.zzzzz.mock

import android.content.Context
import com.google.gson.Gson
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer
import kotlin.random.Random

/**
 * GET /v1/images/{id}?w=160|320|480|720|1080 → JPEG rộng đúng w, delay ngẫu nhiên 0,5–2 s
 * GET /v1/feed?page=0&size=30               → JSON danh sách ô cho lưới 2 cột
 */
internal class FakeImageDispatcher(context: Context) : Dispatcher() {

    private val images = SampleImageFactory(context.applicationContext)
    private val gson = Gson()
    private val feedImageIds: IntArray = buildFeedImageIds()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val segments = url.pathSegments
        return when {
            request.method != "GET" -> error(405, "Method not allowed")
            segments.size == 3 && segments[0] == "v1" && segments[1] == "images" ->
                serveImage(segments[2], url.queryParameter("w"), request.target)
            segments == listOf("v1", "feed") ->
                serveFeed(url.queryParameter("page"), url.queryParameter("size"))
            else -> error(404, "Not found: ${url.encodedPath}")
        }
    }

    private fun serveImage(rawId: String, rawWidth: String?, target: String): MockResponse {
        val id = rawId.toIntOrNull()
        if (id == null || id !in 1..FakeServerConfig.IMAGE_COUNT) {
            return error(404, "Unknown image id: $rawId")
        }
        val width = rawWidth?.toIntOrNull()
        if (width == null || width !in FakeServerConfig.ALLOWED_WIDTHS) {
            return error(400, "w must be one of ${FakeServerConfig.ALLOWED_WIDTHS}")
        }

        FakeServerStats.onImageRequestStart(target)
        var sent = 0
        try {
            // Ngủ ngay trong dispatch (mỗi connection có thread riêng) để thời gian "đang tải"
            // khớp với số inFlight trong FakeServerStats.
            Thread.sleep(randomDelayMs())

            if (Random.nextDouble() < FakeServerConfig.errorRate) {
                return error(500, "Injected failure")
            }

            val jpeg = images.render(id, width)
            sent = jpeg.size
            return MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "image/jpeg")
                .body(Buffer().write(jpeg))
                .build()
        } finally {
            FakeServerStats.onImageRequestEnd(sent)
        }
    }

    private fun serveFeed(rawPage: String?, rawSize: String?): MockResponse {
        val page = rawPage?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val size = rawSize?.toIntOrNull()?.coerceIn(1, 100) ?: 30
        Thread.sleep(FakeServerConfig.feedDelayMs)

        val from = (page * size).coerceAtMost(feedImageIds.size)
        val to = (from + size).coerceAtMost(feedImageIds.size)
        val items = (from until to).map { index ->
            val imageId = feedImageIds[index]
            FeedItem(
                id = "item_$index",
                imageId = imageId,
                title = "Ảnh #$imageId",
                imageUrl = FakeImageServer.imageUrl(imageId),
            )
        }
        val body = FeedResponse(
            page = page,
            size = size,
            total = feedImageIds.size,
            hasMore = to < feedImageIds.size,
            items = items,
        )
        return MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .body(gson.toJson(body))
            .build()
    }

    private fun randomDelayMs(): Long {
        val min = FakeServerConfig.minDelayMs
        val max = FakeServerConfig.maxDelayMs
        return if (max > min) Random.nextLong(min, max + 1) else min
    }

    private fun error(code: Int, message: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .body(gson.toJson(mapOf("error" to message)))
            .build()

    /**
     * Thứ tự imageId của feed, cố định (deterministic) để mọi lần chạy giống nhau:
     * - Cứ 6 ô thì ô thứ 6 trùng ảnh với ô ngay trước nó → 2 ô cùng hàng, cùng URL, hiện
     *   cùng lúc → kiểm "nhiều ô cùng URL → 1 request".
     * - Id chạy 1..IMAGE_COUNT rồi quay vòng → cuối feed gặp lại ảnh đầu feed → kiểm cache
     *   RAM/đĩa khi cuộn xa.
     */
    private fun buildFeedImageIds(): IntArray {
        val ids = IntArray(FakeServerConfig.FEED_TOTAL)
        var next = 0
        for (i in ids.indices) {
            ids[i] = if (i % 6 == 5) {
                ids[i - 1]
            } else {
                next = next % FakeServerConfig.IMAGE_COUNT + 1
                next
            }
        }
        return ids
    }
}
