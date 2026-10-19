package com.example.zzzzz.loader

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UrlDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var downloader: UrlDownloader

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        dir = File(tempFolder.root, "downloads")
        // Chạy callback ngay trên thread OkHttp: unit test JVM không có main Looper
        downloader = UrlDownloader(OkHttpClient(), dir, callbackExecutor = { it.run() })
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `đuôi không phải ảnh video thì báo lỗi ngay, không gọi mạng`() {
        val result = load(server.url("/files/report.pdf").toString())

        val error = result.error as DownloadError.UnsupportedType
        assertTrue(error.detail.contains("pdf"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `url sai định dạng thì báo InvalidUrl`() {
        val result = load("not a url")

        assertTrue(result.error is DownloadError.InvalidUrl)
    }

    @Test
    fun `đuôi ảnh thì tải về file đúng nội dung, không sót tmp`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        server.enqueue(media("image/jpeg", bytes))

        val result = load(server.url("/photos/cat.JPG?w=720").toString())

        assertEquals(MediaKind.IMAGE, result.kind)
        assertTrue(result.file!!.name.endsWith(".jpg"))
        assertArrayEquals(bytes, result.file!!.readBytes())
        assertEquals(listOf(result.file!!.name), dir.list()!!.toList())
    }

    @Test
    fun `đuôi video thì kind là VIDEO`() {
        server.enqueue(media("video/mp4", byteArrayOf(9, 9)))

        val result = load(server.url("/clips/intro.mp4").toString())

        assertEquals(MediaKind.VIDEO, result.kind)
    }

    @Test
    fun `url không đuôi thì dựa vào Content-Type`() {
        server.enqueue(media("image/jpeg", byteArrayOf(7)))

        val result = load(server.url("/v1/images/12?w=720").toString())

        assertEquals(MediaKind.IMAGE, result.kind)
        assertTrue(result.file!!.name.endsWith(".jpg"))
    }

    @Test
    fun `url không đuôi mà server trả HTML thì báo lỗi, không ghi file`() {
        server.enqueue(media("text/html", "<html>login</html>".toByteArray()))

        val result = load(server.url("/v1/images/12").toString())

        assertTrue(result.error is DownloadError.UnsupportedType)
        assertTrue(dir.list().isNullOrEmpty())
    }

    @Test
    fun `HTTP lỗi thì báo mã lỗi`() {
        server.enqueue(MockResponse.Builder().code(404).build())

        val result = load(server.url("/photos/missing.png").toString())

        assertEquals(404, (result.error as DownloadError.Http).code)
    }

    @Test
    fun `huỷ trước khi xong thì không bắn callback`() {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "image/jpeg")
                .body(Buffer().write(byteArrayOf(1)))
                .headersDelay(500, TimeUnit.MILLISECONDS)
                .build(),
        )
        val latch = CountDownLatch(1)
        val handle = downloader.loadUrl(server.url("/a.jpg").toString(), object : DownloadCallback {
            override fun onSuccess(url: String, file: File, kind: MediaKind) = latch.countDown()
            override fun onError(url: String, error: DownloadError) = latch.countDown()
        })

        handle.cancel()

        assertEquals(false, latch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `lần 2 cùng URL thì GET từ file có sẵn, không gọi mạng`() {
        server.enqueue(media("image/png", byteArrayOf(1, 2)))
        val url = server.url("/photos/dog.png").toString()

        val first = load(url)
        val second = load(url)

        assertEquals(1, server.requestCount)
        assertEquals(first.file, second.file)
        assertEquals(MediaKind.IMAGE, second.kind)
    }

    @Test
    fun `url không đuôi cũng GET được từ file có sẵn`() {
        server.enqueue(media("image/jpeg", byteArrayOf(3)))
        val url = server.url("/v1/images/7?w=320").toString()

        load(url)
        val second = load(url)

        assertEquals(1, server.requestCount)
        assertEquals(MediaKind.IMAGE, second.kind)
    }

    @Test
    fun `Content-Type lạ vẫn nhận ra là ảnh khi GET lại`() {
        server.enqueue(media("image/x-custom", byteArrayOf(4)))
        val url = server.url("/v1/images/8").toString()

        load(url)
        val second = load(url)

        assertEquals(1, server.requestCount)
        assertEquals(MediaKind.IMAGE, second.kind)
    }

    @Test
    fun `forceRefresh thì luôn DOWNLOAD lại và ghi đè`() {
        server.enqueue(media("image/jpeg", byteArrayOf(1)))
        server.enqueue(media("image/jpeg", byteArrayOf(2)))
        val url = server.url("/photos/new.jpg").toString()

        load(url)
        val refreshed = load(url, forceRefresh = true)

        assertEquals(2, server.requestCount)
        assertArrayEquals(byteArrayOf(2), refreshed.file!!.readBytes())
        assertEquals(1, dir.list()!!.size)
    }

    @Test
    fun `vượt giới hạn dung lượng thì xoá file lâu không dùng nhất`() {
        downloader = UrlDownloader(OkHttpClient(), dir, callbackExecutor = { it.run() }, maxCacheBytes = 10)
        repeat(3) { server.enqueue(media("image/jpeg", ByteArray(4))) }
        val now = System.currentTimeMillis()

        val oldest = load(server.url("/a.jpg").toString()).file!!
        oldest.setLastModified(now - 3_000)
        val middle = load(server.url("/b.jpg").toString()).file!!
        middle.setLastModified(now - 2_000)
        val newest = load(server.url("/c.jpg").toString()).file!!

        // 3 file × 4 byte = 12 > 10 → xoá file cũ nhất, còn 8 byte
        assertEquals(false, oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun `khởi tạo thì dọn file tmp sót lại từ lần chạy trước`() {
        dir.mkdirs()
        val staleTmp = File(dir, "abc.jpg.123.tmp").apply {
            writeText("dở dang")
            setLastModified(System.currentTimeMillis() - 60_000)
        }

        UrlDownloader(OkHttpClient(), dir, callbackExecutor = { it.run() })

        val deadline = System.currentTimeMillis() + 2_000
        while (staleTmp.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(false, staleTmp.exists())
    }

    private fun media(contentType: String, bytes: ByteArray) =
        MockResponse.Builder()
            .setHeader("Content-Type", contentType)
            .body(Buffer().write(bytes))
            .build()

    private class Result(val file: File?, val kind: MediaKind?, val error: DownloadError?)

    private fun load(url: String, forceRefresh: Boolean = false): Result {
        val latch = CountDownLatch(1)
        var result: Result? = null
        downloader.loadUrl(url, forceRefresh = forceRefresh, callback = object : DownloadCallback {
            override fun onSuccess(url: String, file: File, kind: MediaKind) {
                result = Result(file, kind, null)
                latch.countDown()
            }

            override fun onError(url: String, error: DownloadError) {
                result = Result(null, null, error)
                latch.countDown()
            }
        })
        assertTrue("callback không được gọi", latch.await(5, TimeUnit.SECONDS))
        return result!!
    }
}
