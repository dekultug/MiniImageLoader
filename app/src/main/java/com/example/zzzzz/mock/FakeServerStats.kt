package com.example.zzzzz.mock

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Đếm phía server — dùng ở Màn Dev để chứng minh loader làm đúng:
 * - [duplicateImageRequests] > 0 nghĩa là cùng 1 URL bị tải nhiều lần
 *   (chưa gộp request, hoặc cache đĩa không trúng).
 * - [bytesServed] để so lưu lượng mạng giữa Mini và Coil.
 */
object FakeServerStats {
    private val perUrl = ConcurrentHashMap<String, AtomicInteger>()
    private val bytes = AtomicLong()
    private val inFlight = AtomicInteger()
    private val peakInFlight = AtomicInteger()

    val imageRequests: Int get() = perUrl.values.sumOf { it.get() }
    val uniqueImageUrls: Int get() = perUrl.size
    val duplicateImageRequests: Int get() = imageRequests - uniqueImageUrls
    val bytesServed: Long get() = bytes.get()
    val inFlightRequests: Int get() = inFlight.get()

    /** Số request ảnh đồng thời cao nhất — cho biết loader có giới hạn song song hay không. */
    val peakInFlightRequests: Int get() = peakInFlight.get()

    fun requestsFor(pathAndQuery: String): Int = perUrl[pathAndQuery]?.get() ?: 0

    internal fun onImageRequestStart(pathAndQuery: String) {
        perUrl.getOrPut(pathAndQuery) { AtomicInteger() }.incrementAndGet()
        val now = inFlight.incrementAndGet()
        peakInFlight.accumulateAndGet(now, ::maxOf)
    }

    internal fun onImageRequestEnd(bytesSent: Int) {
        inFlight.decrementAndGet()
        bytes.addAndGet(bytesSent.toLong())
    }

    fun reset() {
        perUrl.clear()
        bytes.set(0)
        peakInFlight.set(inFlight.get())
    }

    fun summary(): String =
        "requests=$imageRequests unique=$uniqueImageUrls dup=$duplicateImageRequests " +
            "bytes=${bytesServed / 1024}KB inFlight=$inFlightRequests peak=$peakInFlightRequests"
}
