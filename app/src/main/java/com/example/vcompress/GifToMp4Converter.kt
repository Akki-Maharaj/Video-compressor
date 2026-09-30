package com.example.vcompress

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.InputStream

object GifToMp4Converter {
    private const val TAG = "GifToMp4"

    data class GifFrame(val bitmap: Bitmap, val durationMs: Int)

    fun convert(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        return try {
            val frames = decodeGifFrames(context, inputUri)
            if (frames.isEmpty()) {
                Log.e(TAG, "No frames decoded from GIF")
                return false
            }
            encodeFramesToMp4(frames, outputFile, onProgress, isCancelled)
        } catch (e: Exception) {
            Log.e(TAG, "GIF to MP4 conversion failed", e)
            false
        }
    }

    private fun decodeGifFrames(context: Context, uri: Uri): List<GifFrame> {
        val frames = mutableListOf<GifFrame>()
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: return emptyList()

        inputStream.use { stream ->
            val parser = SimpleGifDecoder()
            val parsedFrames = parser.read(stream)
            frames.addAll(parsedFrames)
        }
        return frames
    }

    private fun encodeFramesToMp4(
        frames: List<GifFrame>,
        outputFile: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        if (frames.isEmpty()) return false

        var width = frames[0].bitmap.width
        var height = frames[0].bitmap.height

        // Dimensions must be even for H.264
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--

        val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
        val bitrate = 12_000_000 // 12 Mbps for lossless visual appearance
        val iFrameInterval = 1 // 1 second keyframe

        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        }

        val encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var presentationTimeUs = 0L

        try {
            val totalFrames = frames.size
            for (i in frames.indices) {
                if (isCancelled()) {
                    encoder.stop()
                    encoder.release()
                    muxer.release()
                    return false
                }

                val frame = frames[i]
                val frameDurUs = (frame.durationMs.coerceAtLeast(20)) * 1000L

                // Render frame onto the inputSurface
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }

                val srcRect = android.graphics.Rect(0, 0, frame.bitmap.width, frame.bitmap.height)
                val dstRect = android.graphics.Rect(0, 0, width, height)
                canvas.drawBitmap(frame.bitmap, srcRect, dstRect, null)
                inputSurface.unlockCanvasAndPost(canvas)

                // Drain encoder
                drainEncoder(encoder, muxer, bufferInfo, false) { tIndex ->
                    trackIndex = tIndex
                    muxerStarted = true
                }

                presentationTimeUs += frameDurUs
                onProgress(i.toFloat() / totalFrames)
            }

            encoder.signalEndOfInputStream()
            drainEncoder(encoder, muxer, bufferInfo, true) { tIndex ->
                trackIndex = tIndex
                muxerStarted = true
            }

            return true
        } finally {
            try {
                encoder.stop()
            } catch (_: Exception) {}
            try {
                encoder.release()
            } catch (_: Exception) {}
            try {
                inputSurface.release()
            } catch (_: Exception) {}
            try {
                if (muxerStarted) {
                    muxer.stop()
                }
            } catch (_: Exception) {}
            try {
                muxer.release()
            } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        onMuxerStart: (Int) -> Unit
    ) {
        val timeoutUs = 10000L
        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                val trackIndex = muxer.addTrack(newFormat)
                muxer.start()
                onMuxerStart(trackIndex)
            } else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus) ?: break
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size != 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(0, encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    // Lightweight pure-Kotlin GIF frame extractor
    class SimpleGifDecoder {
        fun read(inputStream: InputStream): List<GifFrame> {
            val bytes = inputStream.readBytes()
            val frames = mutableListOf<GifFrame>()
            if (bytes.size < 6) return frames

            var pos = 0
            val header = String(bytes, 0, 6)
            if (!header.startsWith("GIF")) return frames
            pos += 6

            val canvasWidth = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            val canvasHeight = (bytes[pos + 2].toInt() and 0xFF) or ((bytes[pos + 3].toInt() and 0xFF) shl 8)
            val packed = bytes[pos + 4].toInt() and 0xFF
            val gctFlag = (packed and 0x80) != 0
            val gctSize = 2 shl (packed and 0x07)
            pos += 7

            var globalColorTable: IntArray? = null
            if (gctFlag) {
                globalColorTable = IntArray(gctSize)
                for (i in 0 until gctSize) {
                    val r = bytes[pos++].toInt() and 0xFF
                    val g = bytes[pos++].toInt() and 0xFF
                    val b = bytes[pos++].toInt() and 0xFF
                    globalColorTable[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            var delayMs = 100
            var transparentIndex = -1
            var hasTransparency = false

            var canvasBitmap = Bitmap.createBitmap(canvasWidth.coerceAtLeast(1), canvasHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

            while (pos < bytes.size) {
                val blockType = bytes[pos++].toInt() and 0xFF
                if (blockType == 0x3B) { // Trailer
                    break
                } else if (blockType == 0x21) { // Extension
                    val extCode = bytes[pos++].toInt() and 0xFF
                    if (extCode == 0xF9) { // Graphic Control Extension
                        val blockSize = bytes[pos++].toInt() and 0xFF
                        val extPacked = bytes[pos].toInt() and 0xFF
                        hasTransparency = (extPacked and 0x01) != 0
                        val delayTime = (bytes[pos + 1].toInt() and 0xFF) or ((bytes[pos + 2].toInt() and 0xFF) shl 8)
                        delayMs = if (delayTime > 0) delayTime * 10 else 100
                        transparentIndex = bytes[pos + 3].toInt() and 0xFF
                        pos += blockSize
                        while (pos < bytes.size && bytes[pos].toInt() != 0) {
                            pos += (bytes[pos].toInt() and 0xFF) + 1
                        }
                        pos++ // skip terminating 0
                    } else {
                        // Skip sub-blocks
                        while (pos < bytes.size) {
                            val len = bytes[pos++].toInt() and 0xFF
                            if (len == 0) break
                            pos += len
                        }
                    }
                } else if (blockType == 0x2C) { // Image Descriptor
                    val left = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
                    val top = (bytes[pos + 2].toInt() and 0xFF) or ((bytes[pos + 3].toInt() and 0xFF) shl 8)
                    val width = (bytes[pos + 4].toInt() and 0xFF) or ((bytes[pos + 5].toInt() and 0xFF) shl 8)
                    val height = (bytes[pos + 6].toInt() and 0xFF) or ((bytes[pos + 7].toInt() and 0xFF) shl 8)
                    val imgPacked = bytes[pos + 8].toInt() and 0xFF
                    val lctFlag = (imgPacked and 0x80) != 0
                    val lctSize = 2 shl (imgPacked and 0x07)
                    pos += 9

                    var localColorTable: IntArray? = null
                    if (lctFlag) {
                        localColorTable = IntArray(lctSize)
                        for (i in 0 until lctSize) {
                            val r = bytes[pos++].toInt() and 0xFF
                            val g = bytes[pos++].toInt() and 0xFF
                            val b = bytes[pos++].toInt() and 0xFF
                            localColorTable[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }

                    val colorTable = localColorTable ?: globalColorTable ?: IntArray(256) { 0xFF000000.toInt() }

                    // LZW Minimum Code Size
                    val lzwMinCodeSize = bytes[pos++].toInt() and 0xFF

                    // Collect image data blocks
                    val imgData = mutableListOf<Byte>()
                    while (pos < bytes.size) {
                        val len = bytes[pos++].toInt() and 0xFF
                        if (len == 0) break
                        for (j in 0 until len) {
                            imgData.add(bytes[pos + j])
                        }
                        pos += len
                    }

                    // Decode LZW
                    val pixels = decodeLzw(imgData.toByteArray(), width * height, lzwMinCodeSize)

                    val frameBitmap = canvasBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val pIndex = y * width + x
                            if (pIndex < pixels.size) {
                                val colorIndex = pixels[pIndex].toInt() and 0xFF
                                if (!hasTransparency || colorIndex != transparentIndex) {
                                    val px = left + x
                                    val py = top + y
                                    if (px < canvasWidth && py < canvasHeight) {
                                        frameBitmap.setPixel(px, py, colorTable[colorIndex % colorTable.size])
                                    }
                                }
                            }
                        }
                    }
                    frames.add(GifFrame(frameBitmap, delayMs))
                    canvasBitmap = frameBitmap
                }
            }
            return frames
        }

        private fun decodeLzw(data: ByteArray, pixelCount: Int, minCodeSize: Int): ByteArray {
            val pixels = ByteArray(pixelCount)
            val clearCode = 1 shl minCodeSize
            val eoiCode = clearCode + 1
            var codeSize = minCodeSize + 1
            var maxCode = 1 shl codeSize
            var available = eoiCode + 1

            val prefix = IntArray(4096)
            val suffix = ByteArray(4096)
            val pixelStack = ByteArray(4097)

            for (i in 0 until clearCode) {
                prefix[i] = -1
                suffix[i] = i.toByte()
            }

            var datum = 0
            var bits = 0
            var first = 0
            var top = 0
            var pi = 0
            var oldCode = -1
            var bi = 0

            while (pi < pixelCount && bi < data.size) {
                if (top == 0) {
                    while (bits < codeSize && bi < data.size) {
                        datum = datum or ((data[bi++].toInt() and 0xFF) shl bits)
                        bits += 8
                    }
                    if (bits < codeSize) break

                    val code = datum and ((1 shl codeSize) - 1)
                    datum = datum shr codeSize
                    bits -= codeSize

                    if (code == clearCode) {
                        codeSize = minCodeSize + 1
                        maxCode = 1 shl codeSize
                        available = eoiCode + 1
                        oldCode = -1
                        continue
                    }
                    if (code == eoiCode) break
                    if (oldCode == -1) {
                        pixels[pi++] = suffix[code]
                        oldCode = code
                        first = code
                        continue
                    }

                    var inCode = code
                    if (code >= available) {
                        pixelStack[top++] = first.toByte()
                        inCode = oldCode
                    }
                    while (inCode >= clearCode) {
                        pixelStack[top++] = suffix[inCode]
                        inCode = prefix[inCode]
                    }
                    first = suffix[inCode].toInt() and 0xFF
                    pixelStack[top++] = suffix[inCode]

                    if (available < 4096) {
                        prefix[available] = oldCode
                        suffix[available] = first.toByte()
                        available++
                        if (available >= maxCode && codeSize < 12) {
                            codeSize++
                            maxCode = 1 shl codeSize
                        }
                    }
                    oldCode = code
                }
                pixels[pi++] = pixelStack[--top]
            }
            return pixels
        }
    }
}
