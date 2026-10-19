package com.example.zzzzz.loader

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

enum class MediaKind { IMAGE, VIDEO }

sealed class DownloadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidUrl(val url: String) :
        DownloadError("URL không hợp lệ: $url")

    /** Đuôi file hoặc Content-Type không phải ảnh/video. */
    class UnsupportedType(val url: String, val detail: String) :
        DownloadError("Không phải ảnh/video ($detail): $url")

    class Http(val url: String, val code: Int) :
        DownloadError("HTTP $code: $url")

    class Network(val url: String, cause: IOException) :
        DownloadError("Lỗi mạng/ghi file: ${cause.message}", cause)
}

interface DownloadCallback {
    fun onSuccess(url: String, file: File, kind: MediaKind)
    fun onError(url: String, error: DownloadError)
}

fun interface Cancellable {
    fun cancel()

    companion object {
        val NOOP = Cancellable { }
    }
}

/**
 * Tải 1 URL về thành file trong [directory].
 *
 * Validate 2 lớp:
 * 1. Trước khi gọi mạng: URL có đuôi mà đuôi không phải ảnh/video (.pdf, .html, .php…)
 *    → [DownloadCallback.onError] ngay, không tốn request nào.
 *    URL không có đuôi (vd `/v1/images/12?w=720` của server giả) thì chưa kết luận được → đi tiếp.
 * 2. Khi có response: Content-Type phải là `image/` hoặc `video/`. Chặn trường hợp server trả
 *    trang lỗi HTML với mã 200, hoặc URL không đuôi nhưng không phải media.
 *
 * Có sẵn file của URL trong [directory] thì trả luôn (GET), chưa có mới tải (DOWNLOAD).
 * File được ghi vào `.tmp` rồi rename → không bao giờ để lại file ảnh ghi dở.
 * Mọi callback chạy trên [callbackExecutor] (mặc định main thread), kể cả lỗi validate,
 * để phía gọi luôn nhận callback trên cùng 1 thread. Sau khi [Cancellable.cancel] thì không
 * bắn callback nào nữa.
 */
class UrlDownloader(
    private val client: OkHttpClient,
    private val directory: File,
    private val callbackExecutor: Executor = MainThreadExecutor,
) {

    /**
     * GET hoặc DOWNLOAD:
     * - File của URL đã có trong [directory] → trả luôn file đó (GET), không gọi mạng.
     * - Chưa có, hoặc [forceRefresh] = true → tải mới (DOWNLOAD), ghi đè file cũ.
     *
     * Kiểm tra file chạy trên thread nền của OkHttp, không chạy trên thread gọi (tránh đọc đĩa
     * trên main thread).
     */
    fun loadUrl(url: String, callback: DownloadCallback, forceRefresh: Boolean = false): Cancellable {
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl == null) {
            deliverError(callback, url, DownloadError.InvalidUrl(url))
            return Cancellable.NOOP
        }

        val extension = httpUrl.extension()
        val kindFromExtension = kindOfExtension(extension)
        if (extension.isNotEmpty() && kindFromExtension == null) {
            deliverError(callback, url, DownloadError.UnsupportedType(url, "đuôi .$extension"))
            return Cancellable.NOOP
        }

        val cancelled = AtomicBoolean(false)
        val call = client.newCall(Request.Builder().url(httpUrl).build())

        client.dispatcher.executorService.execute {
            if (cancelled.get()) return@execute

            if (!forceRefresh) {
                val cached = findCached(url, extension)
                if (cached != null) {
                    // Cập nhật "lần dùng cuối" để sau này xoá file lâu không dùng nhất (LRU)
                    cached.setLastModified(System.currentTimeMillis())
                    val kind = cachedKindOf(cached)
                    if (kind != null && !cancelled.get()) {
                        callbackExecutor.execute { callback.onSuccess(url, cached, kind) }
                        return@execute
                    }
                }
            }

            if (cancelled.get()) return@execute
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) deliverError(callback, url, DownloadError.Network(url, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { handleResponse(call, it, url, extension, kindFromExtension, callback) }
                }
            })
        }

        return Cancellable {
            cancelled.set(true)
            call.cancel()
        }
    }

    /** File đã tải trước đó của [url], hoặc null. Bỏ qua file `.tmp` đang ghi dở. */
    private fun findCached(url: String, extension: String): File? {
        val hash = hashOf(url)
        if (extension.isNotEmpty()) {
            return File(directory, "$hash.$extension").takeIf { it.isFile }
        }
        // URL không đuôi: đuôi file lấy từ Content-Type lúc tải → phải tìm theo tiền tố
        return directory.listFiles { file ->
            file.name.startsWith("$hash.") && !file.name.endsWith(".tmp")
        }?.firstOrNull()
    }

    private fun handleResponse(
        call: Call,
        response: Response,
        url: String,
        extension: String,
        kindFromExtension: MediaKind?,
        callback: DownloadCallback,
    ) {
        if (!response.isSuccessful) {
            deliverError(callback, url, DownloadError.Http(url, response.code))
            return
        }

        val contentType = response.body.contentType()
        val kind = resolveKind(contentType, kindFromExtension)
        if (kind == null) {
            deliverError(callback, url, DownloadError.UnsupportedType(url, "Content-Type $contentType"))
            return
        }

        val target = File(directory, fileName(url, extension, contentType, kind))
        // Tên tmp riêng cho mỗi lần tải: 2 request cùng URL chạy song song không ghi đè nhau
        val tmp = File(directory, "${target.name}.${System.nanoTime()}.tmp")
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Không tạo được thư mục $directory")
            }
            tmp.sink().buffer().use { sink -> sink.writeAll(response.body.source()) }
            if (!tmp.renameTo(target)) throw IOException("Không rename được $tmp → $target")
        } catch (e: IOException) {
            tmp.delete()
            if (!call.isCanceled()) deliverError(callback, url, DownloadError.Network(url, e))
            return
        }

        if (call.isCanceled()) return
        callbackExecutor.execute { callback.onSuccess(url, target, kind) }
    }

    private fun deliverError(callback: DownloadCallback, url: String, error: DownloadError) {
        callbackExecutor.execute { callback.onError(url, error) }
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp")

        fun kindOfExtension(extension: String): MediaKind? = when (extension.lowercase()) {
            in IMAGE_EXTENSIONS -> MediaKind.IMAGE
            in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            else -> null
        }

        /** Đuôi của segment cuối, bỏ qua query: `/a/b/photo.JPG?w=1` → `jpg`, `/v1/images/12` → "". */
        private fun HttpUrl.extension(): String =
            pathSegments.last().substringAfterLast('.', missingDelimiterValue = "").lowercase()

        /**
         * Content-Type thắng nếu server nói rõ image/video. Không có hoặc chung chung
         * (octet-stream) thì tin đuôi file. Còn lại (text/html, application/json…) → null.
         */
        private fun resolveKind(contentType: MediaType?, kindFromExtension: MediaKind?): MediaKind? =
            when (contentType?.type) {
                "image" -> MediaKind.IMAGE
                "video" -> MediaKind.VIDEO
                null -> kindFromExtension
                else -> if (contentType.subtype == "octet-stream") kindFromExtension else null
            }

        private const val FALLBACK_IMAGE_EXTENSION = "img"
        private const val FALLBACK_VIDEO_EXTENSION = "vid"

        private fun hashOf(url: String): String = url.encodeUtf8().sha256().hex()

        /**
         * Tên file = sha256(url) để cùng URL luôn ra cùng file, đuôi lấy từ URL hoặc Content-Type.
         * Đuôi luôn suy ra được [MediaKind] (xem [cachedKindOf]) để lần GET sau biết file là ảnh hay video.
         */
        private fun fileName(url: String, extension: String, contentType: MediaType?, kind: MediaKind): String {
            val ext = extension.ifEmpty {
                val fromSubtype = if (contentType?.subtype == "jpeg") "jpg" else contentType?.subtype.orEmpty()
                when {
                    kindOfExtension(fromSubtype) == kind -> fromSubtype
                    kind == MediaKind.IMAGE -> FALLBACK_IMAGE_EXTENSION
                    else -> FALLBACK_VIDEO_EXTENSION
                }
            }
            return "${hashOf(url)}.$ext"
        }

        private fun cachedKindOf(file: File): MediaKind? = when (file.extension) {
            FALLBACK_IMAGE_EXTENSION -> MediaKind.IMAGE
            FALLBACK_VIDEO_EXTENSION -> MediaKind.VIDEO
            else -> kindOfExtension(file.extension)
        }
    }

    private object MainThreadExecutor : Executor {
        private val handler = Handler(Looper.getMainLooper())
        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }
}
