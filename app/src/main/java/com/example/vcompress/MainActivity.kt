package com.example.vcompress

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var videoDurationMs: Long = 0L
    private var videoSizeBytes: Long = 0L
    private var originalFileName: String = ""
    private var customOutputName: String = ""

    private var targetMb: Double = 8.0
    private val chipValues = listOf(6.0, 8.0, 9.0, 10.0)
    private val chipButtons = mutableListOf<TextView>()

    private var selectedCodec: String = MimeTypes.VIDEO_H265
    private var isResolution720p: Boolean = false
    private var isPreserveAudio: Boolean = true
    private var isPreferencesExpanded: Boolean = false

    private lateinit var emptyVideoState: LinearLayout
    private lateinit var videoDetailCard: LinearLayout
    private lateinit var videoThumbView: ImageView
    private lateinit var durationBadge: TextView
    private lateinit var fileNameText: TextView
    private lateinit var fileSizeText: TextView
    private lateinit var fileMetaText: TextView
    private lateinit var sizeReductionBadge: TextView

    private lateinit var stepperValueText: TextView
    private lateinit var progressBarFill: View
    private lateinit var progressLabel: TextView
    private lateinit var compressButton: Button
    private lateinit var estimatedTimeText: TextView

    private lateinit var preferencesSummaryText: TextView
    private lateinit var preferencesContentLayout: LinearLayout
    private lateinit var chevronIcon: TextView

    private lateinit var codecHevcBtn: TextView
    private lateinit var codecAvcBtn: TextView
    private lateinit var res720pBtn: TextView
    private lateinit var resOrigBtn: TextView
    private lateinit var audioSwitch: Switch

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) onVideoSelected(uri)
    }

    private val pickGifLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) startGifConversion(uri)
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is needed for progress updates", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkNotificationPermission()
        setupStitchUI()
        observeCompressionState()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val testGif = intent.getStringExtra("test_gif")
        if (testGif != null) {
            startGifConversion(Uri.parse(testGif))
            return
        }
        val testVideo = intent.getStringExtra("test_video")
        if (testVideo != null) {
            val uri = Uri.parse(testVideo)
            val mb = intent.getDoubleExtra("test_target_mb", 3.0)
            targetMb = mb
            onVideoSelected(uri)
            if (intent.getBooleanExtra("test_auto_start", false)) {
                startVideoCompression()
            }
            return
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setupStitchUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1220"))
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(40))
        }

        content.addView(buildHeaderView())
        addSpacer(content, 20)

        content.addView(buildSourceVideoCard())
        addSpacer(content, 14)

        content.addView(buildTargetSizeCard())
        addSpacer(content, 14)

        content.addView(buildProgressCard())
        addSpacer(content, 14)

        content.addView(buildEncodingPreferencesCard())
        addSpacer(content, 18)

        compressButton = createCompressButton()
        content.addView(compressButton)

        estimatedTimeText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(14))
            visibility = View.GONE
        }
        content.addView(estimatedTimeText)
        addSpacer(content, 10)

        content.addView(buildAdditionalToolsCard())

        root.addView(content)
        setContentView(root)

        updateReductionBadge()
        updateEstimatedTime()
    }

    private fun buildHeaderView(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconTile = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(12)
            }
            setImageResource(R.drawable.ic_app_logo)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#111A2B"))
                setStroke(dp(1), Color.parseColor("#1E2A40"))
            }
            clipToOutline = true
        }
        row.addView(iconTile)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "Video Compressor"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        textCol.addView(title)

        val subtitle = TextView(this).apply {
            text = "Fast hardware-accelerated GPU engine"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
        }
        textCol.addView(subtitle)

        row.addView(textCol)
        return row
    }

    private fun buildSourceVideoCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(this).apply {
            text = "SOURCE VIDEO"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8A94A6"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(label)

        val renameBtn = TextView(this).apply {
            text = "Rename"
            textSize = 12f
            setTextColor(Color.parseColor("#22D3EE"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { showRenameDialog() }
        }
        headerRow.addView(renameBtn)

        val replaceBtn = TextView(this).apply {
            text = "Replace"
            textSize = 12f
            setTextColor(Color.parseColor("#3B82F6"))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { pickVideoLauncher.launch("video/*") }
        }
        headerRow.addView(replaceBtn)

        card.addView(headerRow)
        addSpacer(card, 12)

        emptyVideoState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1626"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#1E2A40"), dp(4).toFloat(), dp(4).toFloat())
            }
            setOnClickListener { pickVideoLauncher.launch("video/*") }
        }
        val emptyTitle = TextView(this).apply {
            text = "Choose a video to compress"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val emptySub = TextView(this).apply {
            text = "Tap here to pick from your gallery"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        emptyVideoState.addView(emptyTitle)
        emptyVideoState.addView(emptySub)
        card.addView(emptyVideoState)

        videoDetailCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val detailRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val thumbFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                marginEnd = dp(12)
            }
        }
        videoThumbView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1626"))
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
        }
        thumbFrame.addView(videoThumbView)

        durationBadge = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                setMargins(dp(4), 0, 0, dp(4))
            }
        }
        thumbFrame.addView(durationBadge)
        detailRow.addView(thumbFrame)

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        fileNameText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        metaCol.addView(fileNameText)

        fileSizeText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#F5B301"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        }
        metaCol.addView(fileSizeText)

        fileMetaText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#8A94A6"))
        }
        metaCol.addView(fileMetaText)
        detailRow.addView(metaCol)

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(dp(8), dp(8), dp(4), dp(8))
            setOnClickListener { clearSelectedVideo() }
        }
        detailRow.addView(closeBtn)
        videoDetailCard.addView(detailRow)

        card.addView(videoDetailCard)
        return card
    }

    private fun buildTargetSizeCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(this).apply {
            text = "TARGET SIZE"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8A94A6"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(label)

        sizeReductionBadge = TextView(this).apply {
            text = "-82% smaller"
            textSize = 11f
            setTextColor(Color.parseColor("#F87171"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D151B"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#4B1E28"))
            }
        }
        headerRow.addView(sizeReductionBadge)
        card.addView(headerRow)
        addSpacer(card, 14)

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        chipButtons.clear()
        for (value in chipValues) {
            val chip = TextView(this).apply {
                text = "${value.toInt()} MB"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                setOnClickListener {
                    setTargetSize(value)
                }
            }
            chipButtons.add(chip)
            chipRow.addView(chip)
        }
        updateChipSelection()
        card.addView(chipRow)
        addSpacer(card, 16)

        val stepperRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1626"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#1E2A40"))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val minusBtn = createStepperButton("—") {
            setTargetSize((targetMb - 0.5).coerceAtLeast(0.5))
        }
        stepperRow.addView(minusBtn)

        stepperValueText = TextView(this).apply {
            text = String.format(Locale.US, "%.2f Megabytes (MB)", targetMb)
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showCustomTargetDialog() }
        }
        stepperRow.addView(stepperValueText)

        val plusBtn = createStepperButton("+") {
            setTargetSize(targetMb + 0.5)
        }
        stepperRow.addView(plusBtn)

        card.addView(stepperRow)
        return card
    }

    private fun buildProgressCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "COMPRESSION PROGRESS"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8A94A6"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(title)

        progressLabel = TextView(this).apply {
            text = "Ready"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            typeface = Typeface.DEFAULT_BOLD
        }
        headerRow.addView(progressLabel)
        card.addView(headerRow)
        addSpacer(card, 12)

        val track = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B1424"))
                cornerRadius = dp(4).toFloat()
            }
        }

        progressBarFill = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#4F46E5"))
            ).apply {
                cornerRadius = dp(4).toFloat()
            }
        }
        track.addView(progressBarFill)
        card.addView(track)

        return card
    }

    private fun buildEncodingPreferencesCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { toggleEncodingPreferences() }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "ENCODING PREFERENCES"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8A94A6"))
        }
        titleCol.addView(title)

        preferencesSummaryText = TextView(this).apply {
            text = "H.265 • Original • Audio on"
            textSize = 12f
            setTextColor(Color.parseColor("#525F76"))
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(preferencesSummaryText)
        header.addView(titleCol)

        chevronIcon = TextView(this).apply {
            text = "▼"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        header.addView(chevronIcon)
        card.addView(header)

        preferencesContentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        addSpacer(preferencesContentLayout, 14)

        val codecLabel = TextView(this).apply {
            text = "Codec Engine"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
        }
        preferencesContentLayout.addView(codecLabel)
        addSpacer(preferencesContentLayout, 6)

        val codecToggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = createToggleBackground()
            setPadding(dp(3), dp(3), dp(3), dp(3))
            weightSum = 2f
        }
        codecHevcBtn = createSegmentButton("H.265 (HEVC)", true) {
            selectedCodec = MimeTypes.VIDEO_H265
            updatePreferenceViews()
        }
        codecAvcBtn = createSegmentButton("H.264", false) {
            selectedCodec = MimeTypes.VIDEO_H264
            updatePreferenceViews()
        }
        codecToggle.addView(codecHevcBtn)
        codecToggle.addView(codecAvcBtn)
        preferencesContentLayout.addView(codecToggle)
        addSpacer(preferencesContentLayout, 14)

        val resLabel = TextView(this).apply {
            text = "Resolution"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
        }
        preferencesContentLayout.addView(resLabel)
        addSpacer(preferencesContentLayout, 6)

        val resToggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = createToggleBackground()
            setPadding(dp(3), dp(3), dp(3), dp(3))
            weightSum = 2f
        }
        resOrigBtn = createSegmentButton("Original", true) {
            isResolution720p = false
            updatePreferenceViews()
        }
        res720pBtn = createSegmentButton("720p Downscale", false) {
            isResolution720p = true
            updatePreferenceViews()
        }
        resToggle.addView(resOrigBtn)
        resToggle.addView(res720pBtn)
        preferencesContentLayout.addView(resToggle)
        addSpacer(preferencesContentLayout, 14)

        val audioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val audioText = TextView(this).apply {
            text = "Preserve Audio Track"
            textSize = 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        audioRow.addView(audioText)

        audioSwitch = Switch(this).apply {
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                isPreserveAudio = isChecked
                updatePreferenceViews()
                updateEstimatedTime()
            }
        }
        audioRow.addView(audioSwitch)
        preferencesContentLayout.addView(audioRow)

        card.addView(preferencesContentLayout)
        return card
    }

    private fun createCompressButton(): Button {
        return Button(this).apply {
            text = "⚡  Compress Video"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            isEnabled = false
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#3B82F6"))
            ).apply {
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val state = CompressionStateHolder.state.value
                if (state is CompressionProgressState.Running) {
                    cancelCompression()
                } else {
                    startVideoCompression()
                }
            }
        }
    }

    private fun buildAdditionalToolsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable()
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "ADDITIONAL TOOLS"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8A94A6"))
        }
        card.addView(title)
        addSpacer(card, 10)

        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1626"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#1E2A40"))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val gifTitle = TextView(this).apply {
            text = "Convert GIF to MP4"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        textCol.addView(gifTitle)

        val gifSub = TextView(this).apply {
            text = "Turn a GIF into a much smaller MP4, no audio"
            textSize = 11f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(gifSub)
        toolRow.addView(textCol)

        val convertBtn = TextView(this).apply {
            text = "Convert"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4F46E5"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { pickGifLauncher.launch("image/gif") }
        }
        toolRow.addView(convertBtn)

        card.addView(toolRow)
        return card
    }

    private fun setTargetSize(value: Double) {
        targetMb = value
        stepperValueText.text = String.format(Locale.US, "%.2f Megabytes (MB)", targetMb)
        updateChipSelection()
        updateReductionBadge()
    }

    private fun updateChipSelection() {
        for (i in chipValues.indices) {
            val v = chipValues[i]
            val btn = chipButtons[i]
            val isSelected = Math.abs(v - targetMb) < 0.05
            btn.background = GradientDrawable().apply {
                setColor(if (isSelected) Color.parseColor("#1F2E47") else Color.parseColor("#0D1626"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), if (isSelected) Color.parseColor("#22D3EE") else Color.parseColor("#1E2A40"))
            }
            btn.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#8A94A6"))
        }
    }

    private fun updateReductionBadge() {
        if (selectedVideoUri == null || videoSizeBytes <= 0) {
            sizeReductionBadge.text = "Target: ${String.format(Locale.US, "%.1f", targetMb)} MB"
            sizeReductionBadge.setTextColor(Color.parseColor("#8A94A6"))
            (sizeReductionBadge.background as? GradientDrawable)?.apply {
                setColor(Color.parseColor("#141E30"))
                setStroke(dp(1), Color.parseColor("#1E2A40"))
            }
            return
        }

        val origMb = videoSizeBytes.toDouble() / (1024 * 1024)
        if (targetMb >= origMb) {
            sizeReductionBadge.text = "Already smaller than target"
            sizeReductionBadge.setTextColor(Color.parseColor("#8A94A6"))
            (sizeReductionBadge.background as? GradientDrawable)?.apply {
                setColor(Color.parseColor("#141E30"))
                setStroke(dp(1), Color.parseColor("#1E2A40"))
            }
        } else {
            val percent = (((origMb - targetMb) / origMb) * 100).toInt()
            sizeReductionBadge.text = "-${percent}% smaller"
            sizeReductionBadge.setTextColor(Color.parseColor("#F87171"))
            (sizeReductionBadge.background as? GradientDrawable)?.apply {
                setColor(Color.parseColor("#2D151B"))
                setStroke(dp(1), Color.parseColor("#4B1E28"))
            }
        }
    }

    private fun updatePreferenceViews() {
        val codecText = if (selectedCodec == MimeTypes.VIDEO_H265) "H.265" else "H.264"
        val resText = if (isResolution720p) "720p" else "Original"
        val audioText = if (isPreserveAudio) "Audio on" else "Audio off"
        preferencesSummaryText.text = "$codecText • $resText • $audioText"

        updateSegmentButton(codecHevcBtn, selectedCodec == MimeTypes.VIDEO_H265)
        updateSegmentButton(codecAvcBtn, selectedCodec == MimeTypes.VIDEO_H264)
        updateSegmentButton(resOrigBtn, !isResolution720p)
        updateSegmentButton(res720pBtn, isResolution720p)
    }

    private fun toggleEncodingPreferences() {
        isPreferencesExpanded = !isPreferencesExpanded
        if (isPreferencesExpanded) {
            preferencesContentLayout.visibility = View.VISIBLE
            chevronIcon.animate().rotation(180f).setDuration(250).start()
            preferencesSummaryText.visibility = View.GONE
        } else {
            preferencesContentLayout.visibility = View.GONE
            chevronIcon.animate().rotation(0f).setDuration(250).start()
            preferencesSummaryText.visibility = View.VISIBLE
        }
    }

    private fun onVideoSelected(uri: Uri) {
        selectedVideoUri = uri
        emptyVideoState.visibility = View.GONE
        videoDetailCard.visibility = View.VISIBLE
        compressButton.isEnabled = true

        val name = uri.lastPathSegment ?: "video.mp4"
        originalFileName = name
        customOutputName = name.substringBeforeLast(".") + "_compressed"
        fileNameText.text = name

        lifecycleScope.launch(Dispatchers.IO) {
            var duration = 0L
            var size = 0L
            var resolution = ""
            var fps: Float? = null
            var thumb: Bitmap? = null

            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (_: Exception) {}

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@MainActivity, uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: ""
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: ""
                if (width.isNotEmpty() && height.isNotEmpty()) {
                    resolution = "${width}x${height}"
                }
                thumb = retriever.frameAtTime
            } catch (e: Exception) {
                Log.e("MainActivity", "Error extracting video metadata", e)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(this@MainActivity, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        break
                    }
                }
                extractor.release()
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                videoDurationMs = duration
                videoSizeBytes = size

                val durSec = duration / 1000
                val m = durSec / 60
                val s = durSec % 60
                durationBadge.text = String.format(Locale.US, "%d:%02d", m, s)

                val sizeMb = size.toDouble() / (1024 * 1024)
                fileSizeText.text = String.format(Locale.US, "%.1f MB", sizeMb)

                val fpsText = if (fps != null && fps > 0) " @ ${fps.toInt()}fps" else ""
                fileMetaText.text = if (resolution.isNotEmpty()) "$resolution$fpsText" else "Video"

                if (thumb != null) {
                    videoThumbView.setImageBitmap(thumb)
                }

                updateReductionBadge()
                updateEstimatedTime()
            }
        }
    }

    private fun clearSelectedVideo() {
        selectedVideoUri = null
        videoDurationMs = 0L
        videoSizeBytes = 0L
        originalFileName = ""
        customOutputName = ""
        videoDetailCard.visibility = View.GONE
        emptyVideoState.visibility = View.VISIBLE
        compressButton.isEnabled = false
        updateReductionBadge()
        updateEstimatedTime()
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(customOutputName)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1626"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#1E2A40"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Output File")
            .setMessage("Set the filename for the compressed video (.mp4 will be appended):")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                var name = input.text.toString().trim()
                name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (name.isNotEmpty()) {
                    customOutputName = name
                    Toast.makeText(this, "Output name: $name.mp4", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomTargetDialog() {
        val input = EditText(this).apply {
            setText(String.format(Locale.US, "%.1f", targetMb))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1626"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#1E2A40"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Custom Target Size")
            .setMessage("Enter target size in Megabytes (MB):")
            .setView(container)
            .setPositiveButton("Set") { _, _ ->
                val v = input.text.toString().toDoubleOrNull()
                if (v != null && v >= 0.5) {
                    setTargetSize(v)
                } else {
                    Toast.makeText(this, "Minimum target size is 0.5 MB", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateEstimatedTime() {
        if (selectedVideoUri == null || videoDurationMs <= 0) {
            estimatedTimeText.visibility = View.GONE
            return
        }

        val prefs = getSharedPreferences("vcompress_prefs", Context.MODE_PRIVATE)
        val speedRatio = prefs.getFloat("last_speed_ratio", 0f)
        if (speedRatio > 0.05f) {
            val videoSec = videoDurationMs / 1000.0
            val estSec = (videoSec / speedRatio).coerceAtLeast(0.5)
            estimatedTimeText.text = String.format(Locale.US, "Estimated compression time: ~%.1f seconds", estSec)
            estimatedTimeText.visibility = View.VISIBLE
        } else {
            estimatedTimeText.visibility = View.GONE
        }
    }

    private fun observeCompressionState() {
        lifecycleScope.launch {
            CompressionStateHolder.state.collect { state ->
                when (state) {
                    is CompressionProgressState.Idle -> {
                        progressLabel.text = "Ready"
                        progressLabel.setTextColor(Color.parseColor("#8A94A6"))
                        updateProgressWidth(0f)
                        compressButton.text = "⚡  Compress Video"
                        compressButton.isEnabled = selectedVideoUri != null
                        compressButton.background = GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#3B82F6"))
                        ).apply { cornerRadius = dp(14).toFloat() }
                    }
                    is CompressionProgressState.Running -> {
                        progressLabel.text = state.message
                        progressLabel.setTextColor(Color.parseColor("#22D3EE"))
                        updateProgressWidth(state.progress / 100f)
                        compressButton.text = "✕  Cancel Compression"
                        compressButton.isEnabled = true
                        compressButton.background = GradientDrawable().apply {
                            setColor(Color.parseColor("#DC2626"))
                            cornerRadius = dp(14).toFloat()
                        }
                    }
                    is CompressionProgressState.Done -> {
                        progressLabel.text = state.message
                        progressLabel.setTextColor(Color.parseColor("#4ADE80"))
                        updateProgressWidth(1f)
                        compressButton.text = "⚡  Compress Again"
                        compressButton.isEnabled = true
                        compressButton.background = GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#3B82F6"))
                        ).apply { cornerRadius = dp(14).toFloat() }
                        updateEstimatedTime()
                    }
                    is CompressionProgressState.Failed -> {
                        progressLabel.text = state.error
                        progressLabel.setTextColor(Color.parseColor("#F87171"))
                        progressBarFill.background = GradientDrawable().apply {
                            setColor(Color.parseColor("#F87171"))
                            cornerRadius = dp(4).toFloat()
                        }
                        compressButton.text = "⚡  Compress Video"
                        compressButton.isEnabled = selectedVideoUri != null
                        compressButton.background = GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#3B82F6"))
                        ).apply { cornerRadius = dp(14).toFloat() }
                    }
                }
            }
        }
    }

    private fun updateProgressWidth(fraction: Float) {
        val parent = progressBarFill.parent as? View ?: return
        parent.post {
            val totalWidth = parent.width
            val lp = progressBarFill.layoutParams
            lp.width = (totalWidth * fraction.coerceIn(0f, 1f)).toInt()
            progressBarFill.layoutParams = lp
            progressBarFill.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#4F46E5"))
            ).apply { cornerRadius = dp(4).toFloat() }
        }
    }

    private fun startVideoCompression() {
        val uri = selectedVideoUri ?: return
        val finalOutput = if (customOutputName.isNotEmpty()) customOutputName else (originalFileName.substringBeforeLast(".") + "_compressed")

        val intent = Intent(this, CompressionService::class.java).apply {
            action = CompressionService.ACTION_START_VIDEO
            putExtra(CompressionService.EXTRA_INPUT_URI, uri)
            putExtra(CompressionService.EXTRA_TARGET_MB, targetMb)
            putExtra(CompressionService.EXTRA_OUTPUT_NAME, finalOutput)
            putExtra(CompressionService.EXTRA_CODEC, selectedCodec)
            putExtra(CompressionService.EXTRA_RESOLUTION_720P, isResolution720p)
            putExtra(CompressionService.EXTRA_PRESERVE_AUDIO, isPreserveAudio)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Compression started in background", Toast.LENGTH_SHORT).show()
    }

    private fun cancelCompression() {
        val intent = Intent(this, CompressionService::class.java).apply {
            action = CompressionService.ACTION_CANCEL
        }
        startService(intent)
        Toast.makeText(this, "Compression cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun startGifConversion(uri: Uri) {
        val intent = Intent(this, CompressionService::class.java).apply {
            action = CompressionService.ACTION_START_GIF
            putExtra(CompressionService.EXTRA_INPUT_URI, uri)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "GIF conversion started in background", Toast.LENGTH_SHORT).show()
    }

    private fun createCardDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#111A2B"))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.parseColor("#1E2A40"))
        }
    }

    private fun createToggleBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#0B1424"))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.parseColor("#1E2A40"))
        }
    }

    private fun createStepperButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F2E47"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createSegmentButton(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            updateSegmentButton(this, selected)
            setOnClickListener { onClick() }
        }
    }

    private fun updateSegmentButton(btn: TextView, selected: Boolean) {
        btn.background = GradientDrawable().apply {
            setColor(if (selected) Color.parseColor("#1F2E47") else Color.TRANSPARENT)
            cornerRadius = dp(8).toFloat()
            if (selected) setStroke(dp(1), Color.parseColor("#22D3EE"))
        }
        btn.setTextColor(if (selected) Color.WHITE else Color.parseColor("#8A94A6"))
    }

    private fun addSpacer(parent: LinearLayout, dpSize: Int) {
        val v = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(dpSize)
            )
        }
        parent.addView(v)
    }
}
