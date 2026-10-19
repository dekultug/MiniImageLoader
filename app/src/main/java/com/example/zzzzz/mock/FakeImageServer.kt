package com.example.zzzzz.mock

import android.content.Context
import android.util.Log
import mockwebserver3.MockWebServer
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * MockWebServer chạy ngay trong process app, lắng nghe ở http://127.0.0.1:8765.
 * Gọi [start] trong Application.onCreate trước khi có request nào.
 */
object FakeImageServer {
    private const val TAG = "FakeImageServer"

    const val BASE_URL = "http://${FakeServerConfig.HOST}:${FakeServerConfig.PORT}/"

    private var server: MockWebServer? = null

    /** URL gốc của ảnh, chưa có `?w=`. */
    fun imageUrl(id: Int): String = "${BASE_URL}v1/images/$id"

    fun imageUrl(id: Int, width: Int): String = "${imageUrl(id)}?w=$width"

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val mock = MockWebServer().apply { dispatcher = FakeImageDispatcher(context) }
        // Bind socket ngoài main thread cho StrictMode yên; join để chắc chắn server sẵn sàng
        // trước khi Activity đầu tiên bắn request.
        var failure: Throwable? = null
        thread(name = TAG) {
            try {
                mock.start(InetAddress.getByName(FakeServerConfig.HOST), FakeServerConfig.PORT)
            } catch (t: Throwable) {
                failure = t
            }
        }.join()
        failure?.let {
            Log.e(TAG, "Cannot start fake server on $BASE_URL", it)
            return
        }
        server = mock
        Log.i(TAG, "Fake server started at $BASE_URL")
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
    }
}
