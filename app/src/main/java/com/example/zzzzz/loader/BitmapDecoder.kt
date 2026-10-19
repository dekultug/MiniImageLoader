package com.example.zzzzz.loader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException

/**
 * File ảnh (JPEG/PNG/WebP…) → Bitmap, decode vừa đủ cỡ ô thay vì full ảnh.
 *
 * Đọc 2 lượt:
 * 1. `inJustDecodeBounds = true`: chỉ đọc header lấy width/height, không cấp phát pixel nào.
 * 2. Tính `inSampleSize` rồi decode thật → bitmap nhỏ ngay từ lúc decode
 *    (ảnh 4000×3000 cho ô 500 px: 48 MB → 3 MB), không decode full rồi mới thu nhỏ.
 */
object BitmapDecoder {

    /**
     * @param reqWidth bề rộng ô (px). ≤ 0 → decode nguyên cỡ.
     * @param reqHeight chiều cao ô (px). ≤ 0 → chỉ tính theo bề rộng.
     * @param config [Bitmap.Config.RGB_565] tốn nửa RAM nhưng mất kênh trong suốt — hợp với JPEG.
     * @throws IOException file không tồn tại hoặc không phải ảnh decode được.
     * Hàm chạy đồng bộ và tốn CPU: gọi trên thread nền, đừng gọi trên main thread.
     */
    @Throws(IOException::class)
    fun decodeFile(
        file: File,
        reqWidth: Int,
        reqHeight: Int = 0,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap {
        if (!file.isFile) throw IOException("Không có file: $file")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Không đọc được kích thước ảnh (file hỏng hoặc không phải ảnh): $file")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = config
        }
        return BitmapFactory.decodeFile(file.path, options)
            ?: throw IOException("Decode thất bại: $file")
    }

    /**
     * Luỹ thừa của 2 lớn nhất mà ảnh sau khi chia vẫn ≥ cỡ yêu cầu (không bị mờ khi hiển thị).
     * Vd ảnh 1080 px, ô 500 px → 2 (ra 540 px). Ảnh 720 px, ô 500 px → 1 (chia 2 còn 360 < 500).
     */
    fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        if (reqWidth <= 0 && reqHeight <= 0) return 1
        var sampleSize = 1
        while (fits(srcWidth / (sampleSize * 2), reqWidth) && fits(srcHeight / (sampleSize * 2), reqHeight)) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun fits(size: Int, required: Int) = required <= 0 || size >= required
}
