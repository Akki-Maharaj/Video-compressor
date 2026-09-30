package com.example.vcompress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

@androidx.annotation.OptIn(UnstableApi::class)
class CompressionService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var isCancelled = false

    private lateinit var notificationManager: NotificationManager
    private var currentTransformer: Transformer? = null

    companion object {
        private const val TAG = "CompressionService"
        const val CHANNEL_ID = "compression_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_VIDEO = "ACTION_START_VIDEO"
        const val ACTION_START_GIF = "ACTION_START_GIF"
        const val ACTION_CANCEL = "ACTION_CANCEL"

        const val EXTRA_INPUT_URI = "EXTRA_INPUT_URI"
        const val EXTRA_TARGET_MB = "EXTRA_TARGET_MB"
        const val EXTRA_OUTPUT_NAME = "EXTRA_OUTPUT_NAME"
        const val EXTRA_CODEC = "EXTRA_CODEC"
        const val EXTRA_RESOLUTION_720P = "EXTRA_RESOLUTION_720P"
        const val EXTRA_PRESERVE_AUDIO = "EXTRA_PRESERVE_AUDIO"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            isCancelled = true
            try {
                currentTransformer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling transformer", e)
            }
            CompressionStateHolder.update(CompressionProgressState.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val inputUri = intent?.getParcelableExtra<Uri>(EXTRA_INPUT_URI)
        if (inputUri == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        isCancelled = false
        startForeground(NOTIFICATION_ID, buildProgressNotification("Starting...", 0))
        CompressionStateHolder.update(CompressionProgressState.Running(0, "Starting..."))

        if (action == ACTION_START_VIDEO) {
            val targetMb = intent.getDoubleExtra(EXTRA_TARGET_MB, 8.0)
            val outputName = intent.getStringExtra(EXTRA_OUTPUT_NAME) ?: ("video_" + System.currentTimeMillis() + ".mp4")
            val codec = intent.getStringExtra(EXTRA_CODEC) ?: MimeTypes.VIDEO_H265
            val downscale720p = intent.getBooleanExtra(EXTRA_RESOLUTION_720P, false)
            val preserveAudio = intent.getBooleanExtra(EXTRA_PRESERVE_AUDIO, true)

            startVideoCompression(inputUri, targetMb, outputName, codec, downscale720p, preserveAudio)
        } else if (action == ACTION_START_GIF) {
            startGifConversion(inputUri)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Compression Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of video/GIF compression"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(text: String, progress: Int): Notification {
        val cancelIntent = Intent(this, CompressionService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Video Compressor")
            .setContentText(text)
            .setContentIntent(openAppPending)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun showResultNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun calculateVideoBitrate(uri: Uri, targetMb: Double, preserveAudio: Boolean): Pair<Int, Long> {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        try {
            retriever.setDataSource(this, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durationStr?.toLongOrNull() ?: 10000L
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting duration", e)
            durationMs = 10000L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val safetyMargin = 0.92
        val audioBitrate = if (preserveAudio) 128_000 else 0
        val targetTotalBits = targetMb * 8 * 1024 * 1024
        val targetTotalBitrate = (targetTotalBits * safetyMargin) / durationSec
        val videoBitrate = (targetTotalBitrate - audioBitrate).toInt()
        val minBitrate = 250_000 // Minimum 250 kbps
        return Pair(videoBitrate.coerceAtLeast(minBitrate), durationMs)
    }

    private fun startVideoCompression(
        uri: Uri,
        targetMb: Double,
        outputFileName: String,
        requestedCodec: String,
        downscale720p: Boolean,
        preserveAudio: Boolean
    ) {
        scope.launch {
            val startTime = System.currentTimeMillis()
            val (videoBitrate, durationMs) = withContext(Dispatchers.IO) {
                calculateVideoBitrate(uri, targetMb, preserveAudio)
            }
            Log.i(TAG, "Target: " + targetMb + " MB, Bitrate: " + videoBitrate + " bps, Audio: " + preserveAudio)

            val tempFile = File(cacheDir, "compress_" + System.currentTimeMillis() + ".mp4")
            var usedCodecName = if (requestedCodec == MimeTypes.VIDEO_H265) "HEVC" else "H.264"
            var success = runTransformer(uri, tempFile, requestedCodec, videoBitrate, downscale720p, preserveAudio)

            if (!success && !isCancelled && requestedCodec == MimeTypes.VIDEO_H265) {
                Log.w(TAG, "HEVC failed or unsupported, falling back to H.264")
                if (tempFile.exists()) tempFile.delete()
                usedCodecName = "H.264 (Fallback)"
                success = runTransformer(uri, tempFile, MimeTypes.VIDEO_H264, videoBitrate, downscale720p, preserveAudio)
            }

            if (success && !isCancelled) {
                val finalSizeBytes = tempFile.length()
                val finalSizeMb = finalSizeBytes.toDouble() / (1024 * 1024)

                // Measure speed ratio and store in SharedPreferences
                val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                val durationSec = (durationMs / 1000.0).coerceAtLeast(0.1)
                val speedRatio = durationSec / elapsedSec
                getSharedPreferences("vcompress_prefs", Context.MODE_PRIVATE).edit()
                    .putFloat("last_speed_ratio", speedRatio.toFloat())
                    .apply()

                var origSizeBytes = 0L
                try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        origSizeBytes = pfd.statSize
                    }
                } catch (_: Exception) {}

                val savedPercent = if (origSizeBytes > 0 && origSizeBytes > finalSizeBytes) {
                    (((origSizeBytes - finalSizeBytes).toDouble() / origSizeBytes) * 100).toInt()
                } else 0

                val finalName = if (outputFileName.endsWith(".mp4", ignoreCase = true)) outputFileName else (outputFileName + ".mp4")
                withContext(Dispatchers.IO) {
                    saveToMediaStore(tempFile, finalName, "video/mp4")
                    tempFile.delete()
                }

                val resultMsg = "Done: %.2f MB (saved %d%%)".format(finalSizeMb, savedPercent)
                CompressionStateHolder.update(CompressionProgressState.Done(finalSizeMb, savedPercent, resultMsg))
                showResultNotification("Compression Completed", "Saved to Movies/Compressed: " + resultMsg)
            } else if (!isCancelled) {
                if (tempFile.exists()) tempFile.delete()
                CompressionStateHolder.update(CompressionProgressState.Failed("Compression failed"))
                showResultNotification("Compression Failed", "Could not compress the selected video.")
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runTransformer(
        inputUri: Uri,
        outputFile: File,
        videoMimeType: String,
        videoBitrate: Int,
        downscale720p: Boolean,
        preserveAudio: Boolean
    ): Boolean {
        var completed = false
        var failed = false

        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(videoBitrate)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .setEnableFallback(true)
            .build()

        val transformerBuilder = Transformer.Builder(this)
            .setLooper(Looper.getMainLooper())
            .setEncoderFactory(encoderFactory)
            .setVideoMimeType(videoMimeType)

        if (preserveAudio) {
            transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
        }

        transformerBuilder.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                completed = true
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                Log.e(TAG, "Transformer error: " + exportException.message, exportException)
                failed = true
            }
        })

        val transformer = transformerBuilder.build()
        currentTransformer = transformer

        val mediaItem = MediaItem.fromUri(inputUri)
        val editedMediaItemBuilder = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(!preserveAudio)

        if (downscale720p) {
            // Downscale to 720p short-side without upscaling
            val presentation = Presentation.createForShortSide(720)
            val effects: ImmutableList<Effect> = ImmutableList.of(presentation)
            editedMediaItemBuilder.setEffects(androidx.media3.transformer.Effects(ImmutableList.of(), effects))
        }

        val editedMediaItem = editedMediaItemBuilder.build()
        transformer.start(editedMediaItem, outputFile.absolutePath)

        val progressHolder = ProgressHolder()
        while (!completed && !failed && !isCancelled) {
            delay(350)
            val progressState = transformer.getProgress(progressHolder)
            if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                val p = progressHolder.progress
                val msg = "Compressing... " + p + "%"
                CompressionStateHolder.update(CompressionProgressState.Running(p, msg))
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildProgressNotification(msg, p)
                )
            }
        }

        return completed && !failed
    }

    private fun startGifConversion(uri: Uri) {
        scope.launch {
            val tempFile = File(cacheDir, "gif_" + System.currentTimeMillis() + ".mp4")
            val success = withContext(Dispatchers.IO) {
                GifToMp4Converter.convert(
                    context = this@CompressionService,
                    inputUri = uri,
                    outputFile = tempFile,
                    onProgress = { progressFraction ->
                        val p = (progressFraction * 100).toInt()
                        val msg = "Converting GIF... " + p + "%"
                        CompressionStateHolder.update(CompressionProgressState.Running(p, msg))
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildProgressNotification(msg, p)
                        )
                    },
                    isCancelled = { isCancelled }
                )
            }

            if (success && !isCancelled) {
                val finalSizeMb = tempFile.length().toDouble() / (1024 * 1024)
                withContext(Dispatchers.IO) {
                    saveToMediaStore(tempFile, "gif_" + System.currentTimeMillis() + ".mp4", "video/mp4")
                    tempFile.delete()
                }
                val msg = "Done: %.2f MB".format(finalSizeMb)
                CompressionStateHolder.update(CompressionProgressState.Done(finalSizeMb, 0, msg))
                showResultNotification("GIF Conversion Completed", "Saved to Movies/Compressed (" + msg + ")")
            } else if (!isCancelled) {
                if (tempFile.exists()) tempFile.delete()
                CompressionStateHolder.update(CompressionProgressState.Failed("GIF conversion failed"))
                showResultNotification("GIF Conversion Failed", "Could not convert GIF to MP4.")
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun saveToMediaStore(file: File, displayName: String, mimeType: String): Uri? {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Compressed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, contentValues) ?: return null

        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(file).use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
            return itemUri
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to MediaStore", e)
            resolver.delete(itemUri, null, null)
            return null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
