package com.tuna.proj_01

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class NovelViewerActivity : LocalizedActivity() {

    private data class TextChunk(val index: Int, val text: String)

    private lateinit var btnClose: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnSettingsFloating: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var tvProgress: TextView
    private lateinit var seekQuick: SeekBar
    private lateinit var rvChunks: RecyclerView
    private lateinit var blueLightOverlay: View
    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var quickSeekBar: View

    private val adapter = ChunkAdapter()
    private var snapHelper: PagerSnapHelper? = null

    private var settings = TextViewerSettings()
    private var chunks: List<TextChunk> = emptyList()
    private var fullText: String = ""

    private var folder: File? = null
    private var translatedFile: File? = null
    private var bookId: String = ""
    private var bookTitle: String = ""

    private var saveJob: Job? = null
    private var dialogOpen = false

    private val exportPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) exportCurrentBookToDownloads()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novel_viewer)

        bindViews()
        bindActions()
        bindBook()

        lifecycleScope.launch { loadBook() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TextViewerSettingsStore.settingsFlow(this@NovelViewerActivity).collectLatest {
                    settings = it
                    applySettings()
                }
            }
        }

        saveJob = lifecycleScope.launch {
            while (isActive) {
                savePosition()
                delay(1000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch { savePosition() }
    }

    override fun onDestroy() {
        saveJob?.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!settings.volumeKeyPaging) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { moveByVolume(true); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { moveByVolume(false); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun bindViews() {
        btnClose = findViewById(R.id.btn_close)
        btnSettings = findViewById(R.id.btn_novel_settings)
        btnSettingsFloating = findViewById(R.id.btn_novel_settings_floating)
        tvTitle = findViewById(R.id.tv_novel_title)
        tvProgress = findViewById(R.id.tv_reading_progress)
        seekQuick = findViewById(R.id.seek_quick)
        rvChunks = findViewById(R.id.rv_novel_chunks)
        blueLightOverlay = findViewById(R.id.view_blue_light_overlay)
        root = findViewById(R.id.root_novel_viewer)
        topBar = findViewById(R.id.layout_novel_top_bar)
        quickSeekBar = findViewById(R.id.layout_quick_seek)

        rvChunks.adapter = adapter
        rvChunks.itemAnimator = null
        rvChunks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                updateProgressUi()
            }
        })
    }

    private fun bindActions() {
        btnClose.setOnClickListener { finish() }
        btnSettings.setOnClickListener { showSettingsMenu() }
        btnSettingsFloating.setOnClickListener { showSettingsMenu() }

        seekQuick.max = 1000
        seekQuick.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvProgress.text = getString(R.string.novel_viewer_progress_format, (progress / 10f).roundToInt())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val ratio = ((seekBar?.progress ?: 0) / 1000f).coerceIn(0f, 1f)
                jumpToRatio(ratio)
            }
        })
    }

    private fun bindBook() {
        val path = intent.getStringExtra("book_folder_path")
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        folder = File(path)
        bookId = folder!!.name
        translatedFile = File(folder!!, "translated.txt")
    }

    private suspend fun loadBook() {
        val f = translatedFile
        if (f == null || !f.exists()) {
            showMessageDialog(R.string.novel_translation_empty_file)
            finish()
            return
        }
        bookTitle = BookFileManager.readBookTitle(folder!!)
        tvTitle.text = bookTitle
        fullText = withContext(Dispatchers.IO) { f.readText() }
        chunks = withContext(Dispatchers.Default) { splitChunks(fullText, 2600) }
        adapter.notifyDataSetChanged()
        applyReadingMode()
        restorePosition()
    }

    private fun splitChunks(text: String, size: Int): List<TextChunk> {
        if (text.isBlank()) return listOf(TextChunk(0, ""))
        val result = mutableListOf<TextChunk>()
        var start = 0
        var idx = 0
        while (start < text.length) {
            var end = (start + size).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOf('\n', end)
                if (boundary > start + (size / 3)) end = boundary + 1
            }
            if (end <= start) end = (start + size).coerceAtMost(text.length)
            result += TextChunk(idx++, text.substring(start, end))
            start = end
        }
        return result
    }

    private fun currentIndex(): Int {
        val lm = rvChunks.layoutManager as? LinearLayoutManager ?: return 0
        return lm.findFirstVisibleItemPosition().coerceAtLeast(0)
    }

    private fun updateProgressUi() {
        if (chunks.isEmpty()) {
            tvProgress.text = getString(R.string.novel_viewer_progress_format, 0)
            seekQuick.progress = 0
            return
        }
        val idx = currentIndex().coerceIn(0, chunks.lastIndex)
        val percent = if (chunks.size <= 1) 100 else ((idx.toFloat() / chunks.lastIndex) * 100f).roundToInt()
        tvProgress.text = getString(R.string.novel_viewer_progress_format, percent)
        seekQuick.progress = (percent * 10).coerceIn(0, 1000)
    }

    private fun jumpToRatio(ratio: Float) {
        if (chunks.isEmpty()) return
        val target = (ratio * (chunks.size - 1)).roundToInt().coerceIn(0, chunks.lastIndex)
        rvChunks.post {
            (rvChunks.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, 0)
            updateProgressUi()
        }
    }

    private suspend fun savePosition() {
        if (bookId.isBlank() || chunks.isEmpty()) return
        val idx = currentIndex().coerceIn(0, chunks.lastIndex)
        val percent = if (chunks.size <= 1) 100 else ((idx.toFloat() / chunks.lastIndex) * 100f).roundToInt()
        TextViewerSettingsStore.saveBookPosition(this, bookId, TextViewerBookPosition(idx, percent))
    }

    private fun restorePosition() {
        lifecycleScope.launch {
            val saved = TextViewerSettingsStore.loadBookPosition(this@NovelViewerActivity, bookId)
            val target = saved.chunkIndex.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
            rvChunks.post {
                (rvChunks.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, 0)
                updateProgressUi()
            }
        }
    }

    private fun applySettings() {
        applyTheme()
        applyBrightness(settings.brightness)
        applyBlueLight(settings.blueLightPercent)
        applyImmersive(settings.immersiveMode)
        applyOrientation(settings.orientationLock)
        applyReadingMode()
        adapter.notifyDataSetChanged()
        updateProgressUi()
    }

    private fun applyTheme() {
        val palette = when (settings.theme) {
            TextViewerTheme.LIGHT -> intArrayOf(
                Color.parseColor("#F6F7FA"),
                Color.WHITE,
                Color.parseColor("#1A1E28"),
                Color.parseColor("#5A6275")
            )
            TextViewerTheme.DARK -> intArrayOf(
                Color.parseColor("#10141C"),
                Color.parseColor("#151C27"),
                Color.parseColor("#E8EDF8"),
                Color.parseColor("#AAB4C8")
            )
            TextViewerTheme.SEPIA -> intArrayOf(
                Color.parseColor("#F2E8D5"),
                Color.parseColor("#E9DCC5"),
                Color.parseColor("#4A3A2A"),
                Color.parseColor("#7A6A52")
            )
        }
        root.setBackgroundColor(palette[0])
        topBar.setBackgroundColor(palette[1])
        quickSeekBar.setBackgroundColor(palette[1])
        tvTitle.setTextColor(palette[2])
        tvProgress.setTextColor(palette[3])
        val icon = if (settings.theme == TextViewerTheme.DARK) R.color.text_inverse else R.color.text_primary
        btnClose.imageTintList = ContextCompat.getColorStateList(this, icon)
        btnSettings.imageTintList = ContextCompat.getColorStateList(this, icon)
        btnSettingsFloating.imageTintList = ContextCompat.getColorStateList(this, icon)
    }

    private fun applyBrightness(value: Float) {
        val attr = window.attributes
        attr.screenBrightness = value.coerceIn(0.05f, 1f)
        window.attributes = attr
    }

    private fun applyBlueLight(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == 0) {
            blueLightOverlay.visibility = View.GONE
            return
        }
        val alpha = (clamped * 1.8f).roundToInt().coerceIn(0, 180)
        blueLightOverlay.visibility = View.VISIBLE
        blueLightOverlay.setBackgroundColor(Color.argb(alpha, 255, 178, 94))
    }

    private fun applyImmersive(enabled: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = WindowCompat.getInsetsController(window, window.decorView) ?: return
        if (enabled) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        topBar.visibility = if (enabled) View.GONE else View.VISIBLE
        quickSeekBar.visibility = if (enabled) View.GONE else View.VISIBLE
        btnSettingsFloating.visibility = if (enabled) View.VISIBLE else View.GONE
        btnSettingsFloating.alpha = if (enabled) 0.5f else 1f
        if (enabled) btnSettingsFloating.bringToFront()
    }

    private fun applyOrientation(lock: TextViewerOrientationLock) {
        requestedOrientation = when (lock) {
            TextViewerOrientationLock.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            TextViewerOrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            TextViewerOrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun applyReadingMode() {
        val keepIndex = currentIndex().coerceAtLeast(0)
        val lm = when (settings.readingMode) {
            TextViewerReadingMode.HORIZONTAL -> LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            TextViewerReadingMode.PAGED -> LinearLayoutManager(this, RecyclerView.VERTICAL, false)
            TextViewerReadingMode.VERTICAL -> LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        }
        lm.isItemPrefetchEnabled = true
        lm.initialPrefetchItemCount = if (settings.readingMode == TextViewerReadingMode.VERTICAL) 4 else 2
        rvChunks.layoutManager = lm
        val pagedLike = settings.readingMode != TextViewerReadingMode.VERTICAL
        rvChunks.setItemViewCacheSize(if (pagedLike) 3 else 6)
        adapter.pagedLike = pagedLike
        if (pagedLike) {
            if (snapHelper == null) snapHelper = PagerSnapHelper()
            snapHelper?.attachToRecyclerView(rvChunks)
        } else {
            snapHelper?.attachToRecyclerView(null)
        }
        if (chunks.isNotEmpty()) {
            rvChunks.post {
                lm.scrollToPositionWithOffset(keepIndex.coerceIn(0, chunks.lastIndex), 0)
                updateProgressUi()
            }
        }
    }

    private fun moveByVolume(previous: Boolean) {
        when (settings.readingMode) {
            TextViewerReadingMode.VERTICAL -> {
                val amount = (rvChunks.height * 0.85f).roundToInt()
                rvChunks.smoothScrollBy(0, if (previous) -amount else amount)
            }
            TextViewerReadingMode.PAGED,
            TextViewerReadingMode.HORIZONTAL -> {
                val cur = currentIndex()
                val target = if (previous) cur - 1 else cur + 1
                if (target in chunks.indices) rvChunks.smoothScrollToPosition(target)
            }
        }
    }

    private inner class ChunkAdapter : RecyclerView.Adapter<ChunkAdapter.Holder>() {
        var pagedLike: Boolean = true

        init {
            setHasStableIds(true)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.tv_chunk_text)
            val container: View = view.findViewById(R.id.chunk_container)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.item_novel_chunk, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = chunks.size

        override fun getItemId(position: Int): Long = chunks[position].index.toLong()

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val chunk = chunks[position]

            val paletteText = when (settings.theme) {
                TextViewerTheme.DARK -> Color.parseColor("#E8EDF8")
                TextViewerTheme.LIGHT -> Color.parseColor("#1A1E28")
                TextViewerTheme.SEPIA -> Color.parseColor("#4A3A2A")
            }
            holder.container.setBackgroundColor(Color.TRANSPARENT)
            holder.text.setTextColor(paletteText)
            holder.text.textSize = settings.fontSizeSp
            holder.text.letterSpacing = settings.letterSpacingEm
            holder.text.setLineSpacing(0f, settings.lineHeightMult)
            holder.text.setPadding(dp(settings.marginLeftDp), dp(settings.marginTopDp), dp(settings.marginRightDp), dp(settings.marginBottomDp))

            val span = applyParagraphSpacing(chunk.text, dp(settings.paragraphSpacingDp.toInt()))
            holder.text.text = span

            val lp = holder.itemView.layoutParams as RecyclerView.LayoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = if (pagedLike && rvChunks.height > 0) rvChunks.height else ViewGroup.LayoutParams.WRAP_CONTENT
            holder.itemView.layoutParams = lp
        }
    }

    private fun applyParagraphSpacing(text: String, extraPx: Int): CharSequence {
        if (extraPx <= 0 || !text.contains('\n')) return text
        val builder = SpannableStringBuilder(text)
        var i = 0
        while (i < builder.length) {
            if (builder[i] == '\n') {
                builder.setSpan(ParagraphSpacingSpan(extraPx), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i++
        }
        return builder
    }

    private class ParagraphSpacingSpan(private val extra: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: android.graphics.Paint.FontMetricsInt) {
            if (end > 0 && end <= text.length && text[end - 1] == '\n') {
                fm.descent += extra
                fm.bottom += extra
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun showSettingsMenu() {
        if (dialogOpen) return
        dialogOpen = true

        val dialogView = layoutInflater.inflate(R.layout.dialog_novel_viewer_settings, null)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(dialogView)
        sheet.setOnDismissListener { dialogOpen = false }

        val btnSheetClose = dialogView.findViewById<ImageButton>(R.id.btn_sheet_close)
        val footerActions = dialogView.findViewById<View>(R.id.sheet_footer_actions)
        val scrollBody = dialogView.findViewById<View>(R.id.sheet_scroll)

        val rowReadMode = dialogView.findViewById<View>(R.id.row_setting_read_mode)
        val rowTheme = dialogView.findViewById<View>(R.id.row_setting_theme)
        val rowOrientation = dialogView.findViewById<View>(R.id.row_setting_orientation)
        val tvReadMode = dialogView.findViewById<TextView>(R.id.tv_setting_read_mode_value)
        val tvTheme = dialogView.findViewById<TextView>(R.id.tv_setting_theme_value)
        val tvOrientation = dialogView.findViewById<TextView>(R.id.tv_setting_orientation_value)

        val switchImmersive = dialogView.findViewById<SwitchMaterial>(R.id.switch_setting_immersive)
        val switchVolume = dialogView.findViewById<SwitchMaterial>(R.id.switch_setting_volume_keys)

        val seekBrightness = dialogView.findViewById<SeekBar>(R.id.seek_setting_brightness)
        val tvBrightness = dialogView.findViewById<TextView>(R.id.tv_setting_brightness_value)
        val seekBlueLight = dialogView.findViewById<SeekBar>(R.id.seek_setting_blue_light)
        val tvBlueLight = dialogView.findViewById<TextView>(R.id.tv_setting_blue_light_value)

        val seekFontSize = dialogView.findViewById<SeekBar>(R.id.seek_setting_font_size)
        val tvFontSize = dialogView.findViewById<TextView>(R.id.tv_setting_font_size_value)
        val seekLineHeight = dialogView.findViewById<SeekBar>(R.id.seek_setting_line_height)
        val tvLineHeight = dialogView.findViewById<TextView>(R.id.tv_setting_line_height_value)
        val seekLetterSpacing = dialogView.findViewById<SeekBar>(R.id.seek_setting_letter_spacing)
        val tvLetterSpacing = dialogView.findViewById<TextView>(R.id.tv_setting_letter_spacing_value)
        val seekParagraph = dialogView.findViewById<SeekBar>(R.id.seek_setting_paragraph_spacing)
        val tvParagraph = dialogView.findViewById<TextView>(R.id.tv_setting_paragraph_spacing_value)
        val seekMarginLeft = dialogView.findViewById<SeekBar>(R.id.seek_setting_margin_left)
        val tvMarginLeft = dialogView.findViewById<TextView>(R.id.tv_setting_margin_left_value)
        val seekMarginRight = dialogView.findViewById<SeekBar>(R.id.seek_setting_margin_right)
        val tvMarginRight = dialogView.findViewById<TextView>(R.id.tv_setting_margin_right_value)
        val seekMarginTop = dialogView.findViewById<SeekBar>(R.id.seek_setting_margin_top)
        val tvMarginTop = dialogView.findViewById<TextView>(R.id.tv_setting_margin_top_value)
        val seekMarginBottom = dialogView.findViewById<SeekBar>(R.id.seek_setting_margin_bottom)
        val tvMarginBottom = dialogView.findViewById<TextView>(R.id.tv_setting_margin_bottom_value)

        val btnExport = dialogView.findViewById<Button>(R.id.btn_export_current)
        val btnDeleteCurrent = dialogView.findViewById<Button>(R.id.btn_delete_current)
        val btnDeleteAll = dialogView.findViewById<Button>(R.id.btn_delete_all)

        val activeSettings = settings
        tvReadMode.text = readingModeLabel(activeSettings.readingMode)
        tvTheme.text = themeLabel(activeSettings.theme)
        tvOrientation.text = orientationLabel(activeSettings.orientationLock)
        switchImmersive.isChecked = activeSettings.immersiveMode
        switchVolume.isChecked = activeSettings.volumeKeyPaging

        configureSeek(
            seek = seekBrightness,
            valueView = tvBrightness,
            min = 5,
            max = 100,
            current = (activeSettings.brightness * 100f).roundToInt(),
            valueFormatter = { getString(R.string.novel_setting_value_percent, it) },
            onValueChanged = { value -> updateViewerSettings { it.copy(brightness = value / 100f) } }
        )
        configureSeek(
            seek = seekBlueLight,
            valueView = tvBlueLight,
            min = 0,
            max = 100,
            current = activeSettings.blueLightPercent,
            valueFormatter = { getString(R.string.novel_setting_value_percent, it) },
            onValueChanged = { value -> updateViewerSettings { it.copy(blueLightPercent = value) } }
        )
        configureSeek(
            seek = seekFontSize,
            valueView = tvFontSize,
            min = 12,
            max = 32,
            current = activeSettings.fontSizeSp.roundToInt(),
            valueFormatter = { getString(R.string.novel_setting_value_sp, it) },
            onValueChanged = { value -> updateViewerSettings { it.copy(fontSizeSp = value.toFloat()) } }
        )
        configureSeek(
            seek = seekLineHeight,
            valueView = tvLineHeight,
            min = 100,
            max = 220,
            current = (activeSettings.lineHeightMult * 100f).roundToInt(),
            valueFormatter = { getString(R.string.novel_setting_value_percent, it) },
            onValueChanged = { value -> updateViewerSettings { it.copy(lineHeightMult = value / 100f) } }
        )
        configureSeek(
            seek = seekLetterSpacing,
            valueView = tvLetterSpacing,
            min = 0,
            max = 20,
            current = (activeSettings.letterSpacingEm * 100f).roundToInt(),
            valueFormatter = { getString(R.string.novel_setting_value_em, it / 100f) },
            onValueChanged = { value -> updateViewerSettings { it.copy(letterSpacingEm = value / 100f) } }
        )
        configureSeek(
            seek = seekParagraph,
            valueView = tvParagraph,
            min = 0,
            max = 36,
            current = activeSettings.paragraphSpacingDp.roundToInt(),
            valueFormatter = { getString(R.string.novel_setting_value_dp, it) },
            onValueChanged = { value -> updateViewerSettings { it.copy(paragraphSpacingDp = value.toFloat()) } }
        )
        configureSeek(
            seek = seekMarginLeft,
            valueView = tvMarginLeft,
            min = 0,
            max = 48,
            current = activeSettings.marginLeftDp,
            valueFormatter = { getString(R.string.novel_setting_value_dp, it) },
            onValueChanged = { value -> updateViewerSettings { it.copy(marginLeftDp = value) } }
        )
        configureSeek(
            seek = seekMarginRight,
            valueView = tvMarginRight,
            min = 0,
            max = 48,
            current = activeSettings.marginRightDp,
            valueFormatter = { getString(R.string.novel_setting_value_dp, it) },
            onValueChanged = { value -> updateViewerSettings { it.copy(marginRightDp = value) } }
        )
        configureSeek(
            seek = seekMarginTop,
            valueView = tvMarginTop,
            min = 0,
            max = 48,
            current = activeSettings.marginTopDp,
            valueFormatter = { getString(R.string.novel_setting_value_dp, it) },
            onValueChanged = { value -> updateViewerSettings { it.copy(marginTopDp = value) } }
        )
        configureSeek(
            seek = seekMarginBottom,
            valueView = tvMarginBottom,
            min = 0,
            max = 48,
            current = activeSettings.marginBottomDp,
            valueFormatter = { getString(R.string.novel_setting_value_dp, it) },
            onValueChanged = { value -> updateViewerSettings { it.copy(marginBottomDp = value) } }
        )

        rowReadMode.setOnClickListener {
            showReadingModePicker(tvReadMode)
        }
        rowTheme.setOnClickListener {
            showThemePicker(tvTheme)
        }
        rowOrientation.setOnClickListener {
            showOrientationPicker(tvOrientation)
        }
        switchImmersive.setOnCheckedChangeListener { _, isChecked ->
            updateViewerSettings { it.copy(immersiveMode = isChecked) }
        }
        switchVolume.setOnCheckedChangeListener { _, isChecked ->
            updateViewerSettings { it.copy(volumeKeyPaging = isChecked) }
        }

        val hasBook = folder?.exists() == true
        btnExport.isEnabled = hasBook
        btnExport.alpha = if (btnExport.isEnabled) 1f else 0.45f

        btnSheetClose.setOnClickListener { sheet.dismiss() }
        btnExport.setOnClickListener {
            if (!btnExport.isEnabled) return@setOnClickListener
            sheet.dismiss()
            checkExportPermissionAndRun()
        }
        btnDeleteCurrent.setOnClickListener {
            sheet.dismiss()
            confirmDeleteCurrentBook()
        }
        btnDeleteAll.setOnClickListener {
            sheet.dismiss()
            confirmDeleteAllBooksStrongly()
        }

        val footerBaseBottom = footerActions.paddingBottom
        val scrollBaseBottom = scrollBody.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(dialogView) { _, insets ->
            val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottomInset = maxOf(systemBottom, imeBottom)
            footerActions.updatePadding(bottom = footerBaseBottom + bottomInset)
            scrollBody.updatePadding(bottom = scrollBaseBottom + (bottomInset / 2))
            insets
        }

        sheet.setOnShowListener {
            val bottomSheet = sheet.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
            ViewCompat.requestApplyInsets(dialogView)
        }

        sheet.show()
    }

    private fun configureSeek(
        seek: SeekBar,
        valueView: TextView,
        min: Int,
        max: Int,
        current: Int,
        valueFormatter: (Int) -> String,
        onValueChanged: (Int) -> Unit
    ) {
        seek.max = max - min
        seek.progress = (current - min).coerceIn(0, max - min)
        valueView.text = valueFormatter(seek.progress + min)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mapped = progress + min
                valueView.text = valueFormatter(mapped)
                if (fromUser) onValueChanged(mapped)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateViewerSettings(update: (TextViewerSettings) -> TextViewerSettings) {
        lifecycleScope.launch {
            TextViewerSettingsStore.updateSettings(this@NovelViewerActivity, update)
        }
    }

    private fun showThemePicker(valueView: TextView) {
        val items = arrayOf(
            getString(R.string.novel_theme_light),
            getString(R.string.novel_theme_dark),
            getString(R.string.novel_theme_sepia)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.novel_setting_theme)
            .setItems(items) { _, which ->
                val theme = when (which) {
                    0 -> TextViewerTheme.LIGHT
                    1 -> TextViewerTheme.DARK
                    else -> TextViewerTheme.SEPIA
                }
                valueView.text = themeLabel(theme)
                updateViewerSettings { it.copy(theme = theme) }
            }
            .show()
    }

    private fun showReadingModePicker(valueView: TextView) {
        val items = arrayOf(
            getString(R.string.novel_read_mode_paged),
            getString(R.string.novel_read_mode_vertical),
            getString(R.string.novel_read_mode_horizontal)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.novel_setting_read_mode)
            .setItems(items) { _, which ->
                val mode = when (which) {
                    0 -> TextViewerReadingMode.PAGED
                    1 -> TextViewerReadingMode.VERTICAL
                    else -> TextViewerReadingMode.HORIZONTAL
                }
                valueView.text = readingModeLabel(mode)
                updateViewerSettings { it.copy(readingMode = mode) }
            }
            .show()
    }

    private fun showOrientationPicker(valueView: TextView) {
        val items = arrayOf(
            getString(R.string.novel_orientation_system),
            getString(R.string.novel_orientation_portrait),
            getString(R.string.novel_orientation_landscape)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.novel_setting_orientation)
            .setItems(items) { _, which ->
                val orientation = when (which) {
                    1 -> TextViewerOrientationLock.PORTRAIT
                    2 -> TextViewerOrientationLock.LANDSCAPE
                    else -> TextViewerOrientationLock.SYSTEM
                }
                valueView.text = orientationLabel(orientation)
                updateViewerSettings { it.copy(orientationLock = orientation) }
            }
            .show()
    }

    private fun themeLabel(theme: TextViewerTheme): String {
        return when (theme) {
            TextViewerTheme.LIGHT -> getString(R.string.novel_theme_light)
            TextViewerTheme.DARK -> getString(R.string.novel_theme_dark)
            TextViewerTheme.SEPIA -> getString(R.string.novel_theme_sepia)
        }
    }

    private fun readingModeLabel(mode: TextViewerReadingMode): String {
        return when (mode) {
            TextViewerReadingMode.PAGED -> getString(R.string.novel_read_mode_paged)
            TextViewerReadingMode.VERTICAL -> getString(R.string.novel_read_mode_vertical)
            TextViewerReadingMode.HORIZONTAL -> getString(R.string.novel_read_mode_horizontal)
        }
    }

    private fun orientationLabel(lock: TextViewerOrientationLock): String {
        return when (lock) {
            TextViewerOrientationLock.SYSTEM -> getString(R.string.novel_orientation_system)
            TextViewerOrientationLock.PORTRAIT -> getString(R.string.novel_orientation_portrait)
            TextViewerOrientationLock.LANDSCAPE -> getString(R.string.novel_orientation_landscape)
        }
    }

    private fun checkExportPermissionAndRun() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        exportCurrentBookToDownloads()
    }

    private fun exportCurrentBookToDownloads() {
        val current = folder ?: return
        lifecycleScope.launch {
            val result = BookFileManager.exportCurrentBook(this@NovelViewerActivity, current)
            if (result.successCount > 0) {
                showMessageDialog(R.string.novel_viewer_export_done)
            } else {
                showMessageDialog(R.string.novel_viewer_export_fail)
            }
        }
    }

    private fun confirmDeleteCurrentBook() {
        val current = folder ?: return
        lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) { BookMetadataManager.loadMetadata(current) }
            if (metadata.isBookmarked) {
                showMessageDialog(R.string.viewer_delete_block_bookmarked)
                return@launch
            }
            AlertDialog.Builder(this@NovelViewerActivity)
                .setTitle(R.string.viewer_delete_current_title)
                .setMessage(R.string.viewer_delete_current_message)
                .setPositiveButton(R.string.common_delete) { _, _ ->
                    lifecycleScope.launch {
                        BookFileManager.deleteCurrentBook(current)
                        TextViewerSettingsStore.clearBookPosition(this@NovelViewerActivity, bookId)
                        navigateToLibraryAfterDelete()
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun confirmDeleteAllBooksStrongly() {
        AlertDialog.Builder(this@NovelViewerActivity)
            .setTitle(R.string.viewer_delete_all_title)
            .setMessage(R.string.viewer_delete_all_first_confirm)
            .setPositiveButton(R.string.common_continue) { _, _ ->
                val input = EditText(this@NovelViewerActivity).apply {
                    hint = getString(R.string.viewer_delete_all_keyword)
                }
                AlertDialog.Builder(this@NovelViewerActivity)
                    .setTitle(R.string.viewer_delete_all_title)
                    .setMessage(R.string.viewer_delete_all_second_confirm)
                    .setView(input)
                    .setPositiveButton(R.string.common_delete) { _, _ ->
                        if (input.text?.toString()?.trim() != getString(R.string.viewer_delete_all_keyword)) {
                            showMessageDialog(R.string.viewer_delete_all_keyword_mismatch)
                            return@setPositiveButton
                        }
                        lifecycleScope.launch {
                            val deleted = BookFileManager.deleteAllBooks(File(filesDir, "Books"), keepBookmarked = false)
                            TextViewerSettingsStore.clearAllBookPositions(this@NovelViewerActivity)
                            showMessageDialog(getString(R.string.viewer_delete_all_done, deleted))
                            navigateToLibraryAfterDelete()
                        }
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun navigateToLibraryAfterDelete() {
        ImageDataHolder.clear()
        startActivity(Intent(this, LibraryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }
}




