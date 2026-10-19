package com.example.zzzzz.mock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Tạo ảnh cho server giả.
 *
 * - "Ảnh mẫu" được resize sẵn 1 lần cho mỗi width hợp lệ và giữ dưới dạng JPEG bytes
 *   (không giữ Bitmap → không chiếm RAM của app, để phép so RAM Mini vs Coil công bằng).
 * - Mỗi request: decode bản đúng width, vẽ đè số id + viền màu theo id, encode lại JPEG.
 *   Nhờ vậy nhìn là biết ô có bị "nhầm ảnh" khi ViewHolder bị tái dùng hay không.
 *
 * Nếu có file `assets/mock/sample.jpg` thì dùng nó làm ảnh mẫu, không thì tự vẽ một ảnh có
 * nhiễu (để dung lượng JPEG gần giống ảnh chụp thật: ~150–300 KB ở 1080 px).
 */
internal class SampleImageFactory(private val context: Context) {

    private val baseJpegByWidth: Map<Int, ByteArray> by lazy { buildBases() }

    fun render(id: Int, width: Int): ByteArray {
        val base = baseJpegByWidth.getValue(width)
        val bitmap = BitmapFactory.decodeByteArray(
            base, 0, base.size,
            BitmapFactory.Options().apply { inMutable = true },
        )
        try {
            drawOverlay(Canvas(bitmap), id, width, bitmap.height)
            return bitmap.toJpeg()
        } finally {
            bitmap.recycle()
        }
    }

    private fun buildBases(): Map<Int, ByteArray> {
        val source = loadAssetSample() ?: drawProceduralSample(MAX_WIDTH)
        try {
            return FakeServerConfig.ALLOWED_WIDTHS.associateWith { w ->
                val h = (source.height.toLong() * w / source.width).toInt()
                val scaled = Bitmap.createScaledBitmap(source, w, h, true)
                try {
                    scaled.toJpeg()
                } finally {
                    if (scaled !== source) scaled.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun loadAssetSample(): Bitmap? = runCatching {
        context.assets.open("mock/sample.jpg").use { input ->
            // Cắt về hình vuông cho dễ dùng trong lưới 2 cột
            val full = BitmapFactory.decodeStream(input) ?: return null
            val side = minOf(full.width, full.height)
            val square = Bitmap.createBitmap(
                full, (full.width - side) / 2, (full.height - side) / 2, side, side,
            )
            if (square !== full) full.recycle()
            square
        }
    }.getOrNull()

    private fun drawProceduralSample(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFF1E3A5F.toInt(), 0xFF3D7EA6.toInt(), 0xFFF2C14E.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        val random = Random(42)
        repeat(18) {
            val cx = random.nextFloat() * size
            val cy = random.nextFloat() * size
            val r = size * (0.05f + random.nextFloat() * 0.2f)
            paint.shader = RadialGradient(
                cx, cy, r,
                Color.argb(140, random.nextInt(256), random.nextInt(256), random.nextInt(256)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, paint)
        }

        // Nhiễu hạt: làm JPEG nặng như ảnh thật thay vì vài KB của gradient trơn
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (i in pixels.indices) {
            val n = random.nextInt(-28, 29)
            val p = pixels[i]
            pixels[i] = Color.rgb(
                (Color.red(p) + n).coerceIn(0, 255),
                (Color.green(p) + n).coerceIn(0, 255),
                (Color.blue(p) + n).coerceIn(0, 255),
            )
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    private fun drawOverlay(canvas: Canvas, id: Int, w: Int, h: Int) {
        val accent = Color.HSVToColor(floatArrayOf((id * 47f) % 360f, 0.75f, 0.95f))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Viền màu theo id
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.04f
        paint.color = accent
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Số id to ở giữa
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = w * 0.32f
        paint.color = Color.WHITE
        paint.setShadowLayer(w * 0.02f, 0f, 0f, Color.BLACK)
        canvas.drawText("#$id", w / 2f, h / 2f + paint.textSize / 3f, paint)

        // Dải dưới ghi width thật server trả về → dễ kiểm "đúng cỡ ô"
        paint.clearShadowLayer()
        paint.color = 0xAA000000.toInt()
        val bandH = h * 0.14f
        canvas.drawRect(0f, h - bandH, w.toFloat(), h.toFloat(), paint)
        paint.color = accent
        paint.textSize = bandH * 0.55f
        canvas.drawText("w=$w", w / 2f, h - bandH * 0.3f, paint)
    }

    private fun Bitmap.toJpeg(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()

    private companion object {
        val MAX_WIDTH = FakeServerConfig.ALLOWED_WIDTHS.max()
    }
}
