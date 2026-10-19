package com.example.zzzzz

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import androidx.core.view.doOnLayout
import com.example.zzzzz.loader.BitmapDecoder
import com.example.zzzzz.loader.Cancellable
import com.example.zzzzz.loader.DownloadCallback
import com.example.zzzzz.loader.DownloadError
import com.example.zzzzz.loader.MediaKind
import com.example.zzzzz.loader.UrlDownloader
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Luồng 1 lần load: RAM → (trượt) đĩa → (trượt) mạng → ghi đĩa → decode đúng cỡ ô → RAM → ImageView.
 *
 * - Không nhầm ô: request hiện tại của mỗi ImageView nằm trong tag [R.id.mini_image_request];
 *   bitmap chỉ được gán nếu tag vẫn là đúng request đó.
 * - Huỷ khi ô bị tái dùng: [cancel] (gọi trong onViewRecycled, và tự gọi khi load URL mới).
 * - Gộp request: nhiều ô cùng URL dùng chung 1 [DownloadJob] → 1 request mạng.
 *
 * Mọi hàm public gọi trên main thread.
 */
object MiniImageLoader {

    private const val DISK_CACHE_BYTES = 50L * 1024 * 1024

    private val memoryCache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

    /** Decode trên pool cố định: cuộn nhanh không đẻ ra hàng chục thread decode cùng lúc. */
    private val decodeExecutor: ExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))

    private val lock = Any()

    /** URL → lượt tải đang chạy. Chỉ đọc/ghi khi giữ [lock]. */
    private val inFlight = HashMap<String, DownloadJob>()

    private var downloader: UrlDownloader? = null

    private fun init(context: Context) {
        if (downloader != null) return
        val app = context.applicationContext
        downloader = UrlDownloader(
            client = OkHttpClient(),
            directory = File(app.cacheDir, "image_cache"),
            // Callback (kể cả decode) chạy trên pool decode, không chạy trên main
            callbackExecutor = decodeExecutor,
            maxCacheBytes = DISK_CACHE_BYTES,
        )
        app.registerComponentCallbacks(TrimMemoryCallbacks)
    }

    fun loadAndShowImage(
        context: Context,
        url: String,
        target: ImageView,
        placeHolder: Int? = null,
        errorPlaceHolder: Int? = placeHolder,
    ) {
        init(context)

        // Bind lại đúng URL đang tải (vd DiffUtil rebind) → giữ nguyên, không huỷ rồi tải lại
        val current = target.getTag(R.id.mini_image_request) as? ImageRequest
        if (current != null && current.url == url && !current.cancelled) return

        cancel(target)
        val request = ImageRequest(url, target, errorPlaceHolder)
        target.setTag(R.id.mini_image_request, request)
        // Xoá ảnh của item cũ ngay, không để ô tái dùng hiện ảnh sai trong lúc chờ
        if (placeHolder != null) target.setImageResource(placeHolder) else target.setImageDrawable(null)

        // Ô tái dùng đã có cỡ → bắt đầu luôn. Không dùng doOnLayout cho trường hợp này: setImageResource
        // ở trên làm View "chờ layout", nhưng ConstraintLayout thấy cỡ không đổi nên bỏ qua layout
        // ImageView → callback không bao giờ chạy, ô xám mãi.
        if (target.width > 0 && target.height > 0) {
            start(request, target.width, target.height)
            return
        }
        // View mới tạo, chưa được đo → chờ layout lần đầu để biết cỡ ô
        target.doOnLayout {
            if (target.getTag(R.id.mini_image_request) === request) {
                start(request, it.width, it.height)
            }
        }
    }

    /** Huỷ request của ô này. Request mạng dùng chung chỉ bị huỷ khi không còn ô nào chờ nó. */
    fun cancel(target: ImageView) {
        val request = target.getTag(R.id.mini_image_request) as? ImageRequest ?: return
        target.setTag(R.id.mini_image_request, null)
        request.cancelled = true

        val job = request.job ?: return
        val handleToCancel = synchronized(lock) {
            job.waiters.remove(request)
            if (job.waiters.isEmpty() && inFlight[job.url] === job) {
                inFlight.remove(job.url)
                job.cancelled = true
                job.handle
            } else {
                null
            }
        }
        handleToCancel?.cancel()
    }

    fun clearMemory() {
        memoryCache.evictAll()
    }

    private fun start(request: ImageRequest, width: Int, height: Int) {
        request.width = width
        request.height = height

        val cached = memoryCache.get(request.memoryKey)
        if (cached != null) {
            request.target.setImageBitmap(cached)
            request.target.setTag(R.id.mini_image_request, null)
            return
        }

        val downloader = downloader ?: return
        val newJob = synchronized(lock) {
            val running = inFlight[request.url]
            if (running != null) {
                // Gộp request: ô này chỉ đăng ký chờ lượt tải đang chạy
                running.waiters += request
                request.job = running
                return
            }
            DownloadJob(request.url).also {
                it.waiters += request
                request.job = it
                inFlight[request.url] = it
            }
        }

        val handle = downloader.loadUrl(request.url, newJob)
        synchronized(lock) {
            if (newJob.cancelled) handle.cancel() else newJob.handle = handle
        }
    }

    /** Lượt tải dùng chung cho mọi ô cùng URL. Callback chạy trên [decodeExecutor]. */
    private class DownloadJob(val url: String) : DownloadCallback {
        val waiters = ArrayList<ImageRequest>()
        var handle: Cancellable? = null
        var cancelled = false

        override fun onSuccess(url: String, file: File, kind: MediaKind) {
            val active = takeActiveWaiters()
            if (active.isEmpty()) return
            if (kind != MediaKind.IMAGE) {
                active.forEach { deliver(it, null) }
                return
            }
            // Mỗi cỡ ô decode 1 lần, các ô cùng cỡ dùng chung bitmap
            active.groupBy { it.memoryKey }.forEach { (key, sameSize) ->
                val bitmap = memoryCache.get(key) ?: decode(file, sameSize.first())?.also { memoryCache.put(key, it) }
                sameSize.forEach { deliver(it, bitmap) }
            }
        }

        override fun onError(url: String, error: DownloadError) {
            takeActiveWaiters().forEach { deliver(it, null) }
        }

        /** Lấy các ô còn chờ và gỡ job khỏi [inFlight]: từ giờ URL này tải tiếp sẽ trúng cache đĩa. */
        private fun takeActiveWaiters(): List<ImageRequest> = synchronized(lock) {
            if (inFlight[url] === this) inFlight.remove(url)
            val snapshot = waiters.filter { !it.cancelled }
            waiters.clear()
            snapshot
        }
    }

    private fun decode(file: File, request: ImageRequest): Bitmap? =
        try {
            BitmapDecoder.decodeFile(file, request.width, request.height)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            memoryCache.evictAll()
            null
        }

    /** Về main thread, và chỉ gán nếu ô vẫn đang chờ đúng request này (chống nhầm ô). */
    private fun deliver(request: ImageRequest, bitmap: Bitmap?) {
        val target = request.target
        target.post {
            if (target.getTag(R.id.mini_image_request) !== request) return@post
            target.setTag(R.id.mini_image_request, null)
            when {
                bitmap != null -> target.setImageBitmap(bitmap)
                request.errorPlaceHolder != null -> target.setImageResource(request.errorPlaceHolder)
            }
        }
    }

    private class ImageRequest(
        val url: String,
        val target: ImageView,
        val errorPlaceHolder: Int?,
    ) {
        var width = 0
        var height = 0
        var job: DownloadJob? = null

        @Volatile
        var cancelled = false

        /** Cùng URL nhưng khác cỡ ô là 2 bitmap khác nhau. */
        val memoryKey: String get() = "$url@${width}x$height"
    }

    private object TrimMemoryCallbacks : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = clearMemory()
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = clearMemory()
    }
}
