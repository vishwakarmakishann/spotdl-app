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
 */
object MetadataBlockPictureHelper {

    /**
     * Constructs a base64-encoded VorbisComment METADATA_BLOCK_PICTURE string.
     *
     * Format (Big Endian):
     * - Picture Type (32-bit int): 3 = Front Cover
     * - MIME Length (32-bit int) + MIME string (ASCII)
     * - Description Length (32-bit int) + Description string (UTF-8)
     * - Width (32-bit int)
     * - Height (32-bit int)
     * - Color Depth (32-bit int)
     * - Number of Colors (32-bit int, 0 for non-indexed)
     * - Picture Data Length (32-bit int) + Picture raw bytes
     */
    fun buildMetadataBlockPicture(
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg",
        description: String = "Front Cover",
        pictureType: Int = 3
    ): String {
        val mimeBytes = mimeType.toByteArray(Charsets.US_ASCII)
        val descBytes = description.toByteArray(Charsets.UTF_8)

        // Try reading image dimensions if possible
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
     * Checks if image is square (within tolerance) and compresses to JPEG bytes if needed.
     */
    fun ensureSquareJpeg(imageBytes: ByteArray, maxSize: Int = 1400): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes
        val width = bitmap.width
        val height = bitmap.height

        val squareBitmap = if (width != height) {
            val minEdge = minOf(width, height)
            val xOffset = (width - minEdge) / 2
            val yOffset = (height - minEdge) / 2
            Bitmap.createBitmap(bitmap, xOffset, yOffset, minEdge, minEdge)
        } else {
            bitmap
        }

        val scaledBitmap = if (squareBitmap.width > maxSize) {
            Bitmap.createScaledBitmap(squareBitmap, maxSize, maxSize, true)
        } else {
            squareBitmap
        }

        val out = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray()
    }
}

