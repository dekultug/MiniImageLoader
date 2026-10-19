package com.example.zzzzz.loader

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapDecoderTest {

    private fun sample(srcW: Int, srcH: Int, reqW: Int, reqH: Int = 0) =
        BitmapDecoder.calculateInSampleSize(srcW, srcH, reqW, reqH)

    @Test
    fun `ảnh 4000 px cho ô 500 px thì chia 8`() {
        assertEquals(8, sample(4000, 3000, 500))
    }

    @Test
    fun `ảnh 1080 px cho ô 500 px thì chia 2`() {
        assertEquals(2, sample(1080, 1080, 500))
    }

    @Test
    fun `không chia nếu chia xong nhỏ hơn ô`() {
        assertEquals(1, sample(720, 720, 500))
    }

    @Test
    fun `ảnh nhỏ hơn ô thì giữ nguyên`() {
        assertEquals(1, sample(160, 160, 500))
    }

    @Test
    fun `có cả chiều cao thì cạnh nào chạm ngưỡng trước sẽ dừng`() {
        // Chiều rộng cho phép chia 8 (4000/8 = 500), nhưng chiều cao 3000/4 = 750 < 800 → chỉ chia 2
        assertEquals(2, sample(4000, 3000, 500, 800))
    }

    @Test
    fun `không có cỡ yêu cầu thì decode nguyên cỡ`() {
        assertEquals(1, sample(4000, 3000, 0, 0))
    }
}
