package com.example.zzzzz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import com.example.zzzzz.loader.BitmapDecoder
import com.example.zzzzz.loader.DownloadCallback
import com.example.zzzzz.loader.DownloadError
import com.example.zzzzz.loader.MediaKind
import com.example.zzzzz.loader.UrlDownloader
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

object MiniImageLoader {

    private var downloader: UrlDownloader? = null

    private val memoryCache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

    private var diskCacheDir: File? = null
        private set

    private fun init(context: Context) {
        if (downloader != null) return
        val dir = File(context.applicationContext.cacheDir, "image_cache")
        diskCacheDir = dir
        downloader = UrlDownloader(OkHttpClient(), dir, callbackExecutor = { it.run() })
    }

    fun loadAndShowImage(
        context: Context,
        url: String,
        target: ImageView,
        placeHolder: Int? = null,
    ) {
        init(context)
        try {
            val downloader = checkNotNull(downloader) {
                "Chưa gọi MiniImageLoader.init(context) trong Application.onCreate"
            }
            if (memoryCache.get(url) != null) {
                target.setImageBitmap(memoryCache.get(url))
                return
            }
            downloader.loadUrl(url = url, object : DownloadCallback {
                override fun onSuccess(
                    url: String,
                    file: File,
                    kind: MediaKind
                ) {

                    val bitmap = try {
                        BitmapDecoder.decodeFile(file, target.width, target.height)
                    } catch (e: IOException) {
                        target.post { placeHolder?.let(target::setImageResource) }
                        return
                    }
                    memoryCache.put(url, bitmap)
                    target.post { target.setImageBitmap(bitmap) }


                }

                override fun onError(
                    url: String,
                    error: DownloadError
                ) {
                    if (placeHolder != null) {
                        target.post { placeHolder?.let(target::setImageResource) }
                    }
                }
            })

        } catch (e: Exception) {
            if (placeHolder != null) {
                target.post { placeHolder?.let(target::setImageResource) }
            }
        }
    }


}