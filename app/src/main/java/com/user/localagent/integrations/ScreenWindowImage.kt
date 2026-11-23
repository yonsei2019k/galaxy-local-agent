package com.user.localagent.integrations

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ziphap_android.nativeScreenPlayGlyph
import java.security.MessageDigest
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Pixels stay in memory, on the phone. Only bounded labels and opaque refs leave this layer. */
internal object ScreenWindowImage {
    @RequiresApi(34)
    suspend fun capture(service: AccessibilityService, windowId: Int): Bitmap? =
        withTimeoutOrNull(2500) {
            suspendCancellableCoroutine { continuation ->
                service.takeScreenshotOfWindow(windowId, Dispatchers.Default.asExecutor(),
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val bitmap = result.hardwareBuffer.use { buffer ->
                                if (buffer.width > 4096 || buffer.height > 4096) null
                                else Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    ?.let { wrapped ->
                                        try { wrapped.copy(Bitmap.Config.ARGB_8888, false) }
                                        finally { wrapped.recycle() }
                                    }
                            }
                            continuation.resume(bitmap) { _, dropped, _ -> dropped?.recycle() }
                        }
                        override fun onFailure(errorCode: Int) { continuation.resume(null) }
                    })
            }
        }

    data class Line(val text: String, val bounds: Rect)

    /** ML Kit processes the crop on the device. It can return unavailable before its module is ready. */
    suspend fun lines(image: Bitmap, area: Rect): List<Line> {
        val region = Bitmap.createBitmap(image, area.left, area.top, area.width(), area.height())
        val crop = if (region === image) image.copy(Bitmap.Config.ARGB_8888, false) ?: return emptyList() else region
        val reader = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val release = {
            reader.close()
            crop.recycle()
        }
        val task = try { reader.process(InputImage.fromBitmap(crop, 0)) }
        catch (error: RuntimeException) {
            release()
            throw error
        }
        // ML Kit may continue after cancellation, so it owns the crop until completion.
        task.addOnCompleteListener(Executor { it.run() }) { release() }
        return withTimeoutOrNull(2500) {
            task.await().textBlocks.asSequence().flatMap { it.lines.asSequence() }.take(80)
                .mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    Line(line.text.trim().take(120), Rect(box).apply { offset(area.left, area.top) })
                }.toList()
        }.orEmpty()
    }

    fun playPoint(image: Bitmap, region: Rect): Rect? {
        val side = 49
        val half = side / 2
        val x = region.centerX()
        val y = region.centerY()
        for (step in 1..3) {
            val spread = half * step
            val box = Rect(x-spread, y-spread, x+spread+1, y+spread+1)
            if (!region.contains(box)) break
            val luminance = ByteArray(side * side) { index ->
                val pixel = image.getPixel(box.left + index % side * step, box.top + index / side * step)
                ((54 * (pixel shr 16 and 255) + 183 * (pixel shr 8 and 255) + 19 * (pixel and 255)) shr 8).toByte()
            }
            if ((6..20).any { nativeScreenPlayGlyph(luminance, side.toUInt(), it.toUInt()) }) return box
        }
        return null
    }

    fun fingerprint(image: Bitmap, region: Rect): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (y in 0 until 24) for (x in 0 until 24) {
            val pixel = image.getPixel(region.left + (2*x+1)*region.width()/48,
                region.top + (2*y+1)*region.height()/48)
            digest.update((pixel shr 16).toByte())
            digest.update((pixel shr 8).toByte())
            digest.update(pixel.toByte())
        }
        return digest.digest()
    }
}
