package com.example.zzzzz.mock

/** Các núm vặn của server giả. Đổi ở đây (hoặc lúc runtime từ Màn Dev) để thử kịch bản khác. */
object FakeServerConfig {
    const val HOST = "127.0.0.1"
    const val PORT = 8765

    val ALLOWED_WIDTHS = listOf(160, 320, 480, 720, 1080)

    /** Số ảnh khác nhau server có: id hợp lệ là 1..IMAGE_COUNT, ngoài khoảng → 404. */
    const val IMAGE_COUNT = 200

    /** Tổng số item của feed (nhiều hơn IMAGE_COUNT vì có item trùng ảnh). */
    const val FEED_TOTAL = 300

    @Volatile var minDelayMs = 500L
    @Volatile var maxDelayMs = 2_000L

    /** Tỉ lệ request ảnh trả 500, để thử placeholder lỗi. 0.0 = không bao giờ lỗi. */
    @Volatile var errorRate = 0.0

    @Volatile var feedDelayMs = 300L
}
