package com.lihanghang.whatisthis.util

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Shrinks screenshots before they go on the wire: base64 size is upload time,
 * and upload time is part of the "瞬间" budget.
 */
object ImageCodec {

    private const val MAX_DIMENSION = 2048
    private const val PNG_BUDGET_BYTES = 1_500_000

    /** Returns a `data:` URL, or null when the image could not be read. */
    fun toDataUrl(image: Image): String? {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null

        val scale = minOf(1.0, MAX_DIMENSION.toDouble() / maxOf(width, height))
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)

        // Screenshots can arrive as ARGB; flatten onto white so JPEG fallback stays readable.
        val buffered = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val graphics = buffered.createGraphics()
        graphics.drawImage(image, 0, 0, targetW, targetH, java.awt.Color.WHITE, null)
        graphics.dispose()

        val png = runCatching { encode(buffered, "png") }.getOrNull()
        if (png != null && png.size <= PNG_BUDGET_BYTES) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        }
        val jpeg = runCatching { encodeJpeg(buffered, 0.85f) }.getOrNull() ?: return null
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
    }

    private fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, format, out)
            out.toByteArray()
        }

    private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writers = ImageIO.getImageWritersByFormatName("jpg")
        if (!writers.hasNext()) error("no jpeg writer")
        val writer = writers.next()
        val params = writer.defaultWriteParam.apply { compressionQuality = quality }
        return ByteArrayOutputStream().use { out ->
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, javax.imageio.IIOImage(image, null, null), params)
                writer.dispose()
            }
            out.toByteArray()
        }
    }
}
