package com.example.spotdl.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Standard FLAC Picture Block (RFC VorbisComment METADATA_BLOCK_PICTURE) builder.
 * Matches exact mutagen.flac.Picture specification used by the reference CLI engine.
 * Full fidelity: preserves original high-resolution artwork without downscaling or capping.
 */
object MetadataBlockPictureHelper {

    /**
     * Constructs a base64-encoded VorbisComment METADATA_BLOCK_PICTURE string.
     * Preserves full native resolution and bitstream fidelity.
     */
    fun buildMetadataBlockPicture(
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg",
        description: String = "Front Cover",
        pictureType: Int = 3
    ): String {
        val mimeBytes = mimeType.toByteArray(Charsets.US_ASCII)
        val descBytes = description.toByteArray(Charsets.UTF_8)

        // Read image dimensions if available
        var width = 0
        var height = 0
        var depth = 24
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                width = opts.outWidth
                height = opts.outHeight
            }
        } catch (_: Exception) {
            // Keep zeroes if unable to decode dimensions
        }

        val totalSize = 4 + 4 + mimeBytes.size + 4 + descBytes.size + 4 + 4 + 4 + 4 + 4 + imageBytes.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(pictureType)
        buffer.putInt(mimeBytes.size)
        buffer.put(mimeBytes)
        buffer.putInt(descBytes.size)
        buffer.put(descBytes)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putInt(depth)
        buffer.putInt(0) // indexed colors
        buffer.putInt(imageBytes.size)
        buffer.put(imageBytes)

        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    /**
     * Ensures image is a 1:1 square.
     * If the image is already square, returns original bytes directly with zero recompression or quality loss.
     * If not square (e.g. 16:9 video thumbnail fallback), center-crops to square at full native resolution.
     */
    fun ensureSquareJpeg(imageBytes: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes
        val width = bitmap.width
        val height = bitmap.height

        if (width == height) {
            // Already a true 1:1 square master cover art: keep original raw bytes directly
            return imageBytes
        }

        // Center crop without downscaling to keep full resolution
        val minEdge = minOf(width, height)
        val xOffset = (width - minEdge) / 2
        val yOffset = (height - minEdge) / 2
        val squareBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, minEdge, minEdge)

        val out = ByteArrayOutputStream()
        squareBitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
        return out.toByteArray()
    }
}
