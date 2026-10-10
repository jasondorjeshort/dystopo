package io.github.jasondorjeshort.dystopo.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace

/**
 * [ImageDecoder] on Android: BitmapFactory into an unpremultiplied ARGB_8888 bitmap in sRGB, so
 * Terrarium pixels come back exactly as encoded (no colour management, no premultiplication).
 */
object BitmapImageDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): ArgbImage {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("not a decodable image (${bytes.size} bytes)")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return ArgbImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }
}
