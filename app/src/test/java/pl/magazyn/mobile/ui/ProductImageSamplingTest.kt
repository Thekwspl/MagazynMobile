package pl.magazyn.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductImageSamplingTest {
    @Test
    fun largeCameraPhotoIsDownsampledToDisplaySizedBitmap() {
        assertEquals(4, productPhotoSampleSize(width = 4_000, height = 3_000))
    }

    @Test
    fun smallPhotoKeepsOriginalResolution() {
        assertEquals(1, productPhotoSampleSize(width = 1_000, height = 800))
    }
}
