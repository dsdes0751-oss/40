package com.tuna.proj_01

import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ViewerActivity : LocalizedActivity() {
    private val exportPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            exportCurrentBook()
        } else {
            Toast.makeText(this, getString(R.string.settings_storage_permission_required), Toast.LENGTH_SHORT).show()
        }
    }


    // [蹂寃? ViewPager2 ???RecyclerView瑜?硫붿씤 酉곗뼱濡??ъ슜 (?뱁댆 紐⑤뱶 吏?먯쓣 ?꾪빐)
    private lateinit var viewerRecyclerView: RecyclerView
    private lateinit var adapter: ImageSliderAdapter
    private val currentUris = mutableListOf<Uri>()

    private lateinit var repository: BookRepository

    // Controls
    private lateinit var layoutTopBar: View
    private lateinit var layoutBottomBar: View
    private lateinit var tvPageIndicator: TextView
    private lateinit var tvTitle: TextView
    private var isControlsVisible = true

    // [異붽?] 媛?쒖꽦 ?좉? 踰꾪듉 (?꾩뿭 蹂?섎줈 ?밴꺽)
    private lateinit var btnVisibilityToggle: ImageButton

    // ?꾩옱 梨낆쓽 ?대뜑 寃쎈줈 諛?ID
    private var currentBookFolder: File? = null
    private var bookId: String = ""

    private val pageTimestamps = mutableMapOf<Int, Long>()
    private var monitorJob: Job? = null

    // [?ㅼ젙 ?곹깭]
    private var isVolumeKeyNavigationEnabled = false
    private var isVerticalMode = false // ?뱁댆 紐⑤뱶 (?몃줈 ?ㅽ겕濡?
    private var isShowOriginal = false // ?먮낯 蹂닿린 (異뷀썑 援ы쁽)

    // ?ㅻ깄 ?ы띁 (?섏씠吏 紐⑤뱶??
    private var snapHelper: PagerSnapHelper? = null

    // [理쒖쟻?? ?붾㈃ ?덈퉬 誘몃━ 怨꾩궛
    private var screenWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_viewer)

        // [理쒖쟻?? ?붾㈃ ?덈퉬 援ы븯湲?(Glide 由ъ궗?댁쭠??
        screenWidth = resources.displayMetrics.widthPixels

        repository = BookRepository(applicationContext)

        // ?ㅼ젙 遺덈윭?ㅺ린
        val prefs = getSharedPreferences("ViewerSettings", Context.MODE_PRIVATE)
        isVolumeKeyNavigationEnabled = prefs.getBoolean("VolumeKeyNav", false)
        isVerticalMode = prefs.getBoolean("VerticalMode", false)
        isShowOriginal = prefs.getBoolean("ShowOriginal", false)

        // [?듭떖 蹂寃? XML???덈뒗 ViewPager2瑜?李얠븘??遺紐⑤줈遺???쒓굅?섍퀬, 洹??먮━??RecyclerView瑜??ｌ뒿?덈떎.
        replaceViewPagerWithRecyclerView()

        layoutTopBar = findViewById(R.id.layout_top_bar)
        layoutBottomBar = findViewById(R.id.layout_bottom_bar)
        tvPageIndicator = findViewById(R.id.tv_page_indicator)
        tvTitle = findViewById(R.id.tv_title)

        val btnClose = findViewById<ImageButton>(R.id.btn_close)
        val btnSettings = findViewById<ImageButton>(R.id.btn_settings)
        // [湲곕뒫 異붽?] 媛?쒖꽦 ?좉? 踰꾪듉 ?곌껐
        btnVisibilityToggle = findViewById(R.id.btn_visibility_toggle)

        val initialUris = ImageDataHolder.getUris().ifEmpty {
            val strings = intent.getStringArrayListExtra("image_uris")
            strings?.map { Uri.parse(it) } ?: emptyList()
        }
        currentUris.addAll(initialUris)

        val folderPath = intent.getStringExtra("book_folder_path")
        if (folderPath != null) {
            currentBookFolder = File(folderPath)
            bookId = currentBookFolder?.name ?: ""
            tvTitle.text = bookId
        }

        adapter = ImageSliderAdapter(currentUris)
        viewerRecyclerView.adapter = adapter

        // ?쒖옉 ?꾩튂 媛?몄삤湲?
        val startPosition = intent.getIntExtra("start_position", 0)

        // [理쒖쟻?? 酉곗뼱 紐⑤뱶 ?ㅼ젙 ??startPosition???④퍡 ?꾨떖?섏뿬
        // ?덉씠?꾩썐 留ㅻ땲?媛 泥섏쓬遺???대떦 ?꾩튂瑜?洹몃━?꾨줉 ??(0踰덈???洹몃━???ㅻ쾭?ㅻ뱶 ?쒓굅)
        setupViewerMode(startPosition)
        updatePageIndicator()

        btnClose.setOnClickListener { finish() }

        // [?ㅼ젙] Viewer settings ?ㅼ씠?쇰줈洹??몄텧
        btnSettings.setOnClickListener { showViewerSettingsDialog() }

        // [湲곕뒫 異붽?] 媛?쒖꽦 踰꾪듉 ?대┃ ???곹븯?⑤컮 ?좉?
        btnVisibilityToggle.setOnClickListener {
            toggleControls()
        }

        // ?ㅽ겕濡?由ъ뒪??(?섏씠吏 ?몃뵒耳?댄꽣 ?낅뜲?댄듃)
        viewerRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                updatePageIndicator()
            }
        })
    }

    private fun replaceViewPagerWithRecyclerView() {
        val oldPager = findViewById<View>(R.id.view_pager) ?: return
        val parent = oldPager.parent as ViewGroup
        val index = parent.indexOfChild(oldPager)
        val params = oldPager.layoutParams

        parent.removeView(oldPager)

        viewerRecyclerView = RecyclerView(this).apply {
            layoutParams = params
            id = R.id.view_pager // ID ?좎?
            setBackgroundColor(Color.BLACK)
            // [理쒖쟻?? 罹먯떆 ?ъ씠利?議곗젅 (?덈Т ?щ㈃ 硫붾え由?遺議? ?곷떦???ㅼ젙)
            setItemViewCacheSize(3)
        }
        parent.addView(viewerRecyclerView, index)
    }

    /**
     * [理쒖쟻?? LayoutManager ?ㅼ젙 ??珥덇린 ?꾩튂瑜?吏?뺥븯??遺덊븘?뷀븳 濡쒕뵫 諛⑹?
     */
    private fun setupViewerMode(initialPosition: Int = -1) {
        val targetPos = if (initialPosition >= 0) initialPosition else getCurrentPosition()

        if (isVerticalMode) {
            // [?뱁댆 紐⑤뱶] ?몃줈 ?ㅽ겕濡? ?ㅻ깄 ?놁쓬
            val lm = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
            lm.scrollToPositionWithOffset(targetPos, 0) // ?ㅽ봽??0?쇰줈 ?뺥솗???대룞
            viewerRecyclerView.layoutManager = lm
            snapHelper?.attachToRecyclerView(null) // ?ㅻ깄 ?댁젣

            // [UX 蹂寃? ?뱁댆 紐⑤뱶?먯꽌????踰꾪듉 蹂댁씠湲?
            btnVisibilityToggle.visibility = View.VISIBLE
        } else {
            // [?쇰컲 紐⑤뱶] 媛濡??ㅽ겕濡? ?섏씠吏 ?⑥쐞 ?ㅻ깄
            val lm = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            lm.scrollToPosition(targetPos)
            viewerRecyclerView.layoutManager = lm

            if (snapHelper == null) snapHelper = PagerSnapHelper()
            snapHelper?.attachToRecyclerView(viewerRecyclerView)

            // [UX 蹂寃? ?쇰컲 紐⑤뱶?먯꽌????踰꾪듉 ?④린湲?(?곗튂濡?議곗옉)
            btnVisibilityToggle.visibility = View.GONE
        }
        // 紐⑤뱶 蹂寃????대뙌??媛깆떊 (?꾩씠???덉씠?꾩썐 蹂寃??곸슜???꾪빐)
        adapter.notifyDataSetChanged()
    }

    private fun getCurrentPosition(): Int {
        val layoutManager = viewerRecyclerView.layoutManager as LinearLayoutManager?
        return layoutManager?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
    }

    private fun scrollToPosition(position: Int) {
        val layoutManager = viewerRecyclerView.layoutManager as LinearLayoutManager
        layoutManager.scrollToPositionWithOffset(position, 0)
    }

    // [湲곕뒫 ?섏젙] XML ?덉씠?꾩썐(dialog_viewer_settings.xml)???ъ슜?섏뿬 ?ㅼ젙李??꾩슦湲?
    private fun showViewerSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_viewer_settings, null)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(dialogView)

        val switchVertical = dialogView.findViewById<SwitchMaterial>(R.id.switch_vertical_mode)
        val switchOriginal = dialogView.findViewById<SwitchMaterial>(R.id.switch_show_original)
        val switchVolume = dialogView.findViewById<SwitchMaterial>(R.id.switch_volume)
        val switchHideCurrent = dialogView.findViewById<SwitchMaterial>(R.id.switch_hide_current_cover)
        val switchHideAll = dialogView.findViewById<SwitchMaterial>(R.id.switch_hide_all_covers)

        val btnClose = dialogView.findViewById<ImageButton>(R.id.btn_sheet_close)
        val btnExportCurrent = dialogView.findViewById<Button>(R.id.btn_export_current)
        val btnDeleteCurrent = dialogView.findViewById<Button>(R.id.btn_delete_current)
        val btnDeleteAll = dialogView.findViewById<Button>(R.id.btn_delete_all)
        val footerActions = dialogView.findViewById<View>(R.id.sheet_footer_actions)
        val scrollBody = dialogView.findViewById<View>(R.id.sheet_scroll)

        switchVertical.isChecked = isVerticalMode
        switchOriginal.isChecked = isShowOriginal
        switchVolume.isChecked = isVolumeKeyNavigationEnabled

        val prefs = getSharedPreferences("ViewerSettings", Context.MODE_PRIVATE)
        switchHideAll.isChecked = prefs.getBoolean("HideAllCovers", false)

        if (currentBookFolder != null) {
            lifecycleScope.launch {
                val metadata = withContext(Dispatchers.IO) {
                    BookMetadataManager.loadMetadata(currentBookFolder!!)
                }
                switchHideCurrent.isChecked = metadata.isCoverHidden
            }
        }

        val hasBook = currentBookFolder?.exists() == true
        btnExportCurrent.isEnabled = hasBook
        btnExportCurrent.alpha = if (hasBook) 1f else 0.45f

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

        switchVertical.setOnCheckedChangeListener { _, isChecked ->
            isVerticalMode = isChecked
            setupViewerMode()
            saveViewerSettings()
        }

        switchOriginal.setOnCheckedChangeListener { _, isChecked ->
            isShowOriginal = isChecked
            saveViewerSettings()
            if (isChecked) {
                Toast.makeText(this, getString(R.string.viewer_original_not_supported), Toast.LENGTH_SHORT).show()
            }
        }

        switchVolume.setOnCheckedChangeListener { _, isChecked ->
            isVolumeKeyNavigationEnabled = isChecked
            saveViewerSettings()
        }

        switchHideAll.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("HideAllCovers", isChecked).apply()
        }

        switchHideCurrent.setOnCheckedChangeListener { _, isChecked ->
            if (bookId.isNotEmpty()) {
                lifecycleScope.launch {
                    repository.updateCoverVisibility(bookId, isChecked)
                }
            }
        }

        btnClose.setOnClickListener { sheet.dismiss() }

        btnExportCurrent.setOnClickListener {
            if (!btnExportCurrent.isEnabled) return@setOnClickListener
            sheet.dismiss()
            checkExportPermission()
        }

        btnDeleteCurrent.setOnClickListener {
            sheet.dismiss()
            deleteCurrentBook()
        }

        btnDeleteAll.setOnClickListener {
            sheet.dismiss()
            deleteAllBooks()
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

    private fun saveViewerSettings() {
        getSharedPreferences("ViewerSettings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("VolumeKeyNav", isVolumeKeyNavigationEnabled)
            .putBoolean("VerticalMode", isVerticalMode)
            .putBoolean("ShowOriginal", isShowOriginal)
            .apply()
    }

    // [湲곕뒫 異붽?] 蹂쇰ⅷ???대깽??媛濡쒖콈湲?
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKeyNavigationEnabled) {
            val current = getCurrentPosition()

            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    // ?댁쟾 ?섏씠吏
                    if (current > 0) {
                        if (isVerticalMode) {
                            // ?뱁댆 紐⑤뱶?먯꽌??遺?쒕읇寃??ㅽ겕濡?
                            viewerRecyclerView.smoothScrollBy(0, -viewerRecyclerView.height / 2)
                        } else {
                            viewerRecyclerView.smoothScrollToPosition(current - 1)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // ?ㅼ쓬 ?섏씠吏
                    if (current < adapter.itemCount - 1) {
                        if (isVerticalMode) {
                            viewerRecyclerView.smoothScrollBy(0, viewerRecyclerView.height / 2)
                        } else {
                            viewerRecyclerView.smoothScrollToPosition(current + 1)
                        }
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun deleteCurrentBook() {
        val folder = currentBookFolder ?: return
        lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) { BookMetadataManager.loadMetadata(folder) }
            if (metadata.isBookmarked) {
                Toast.makeText(this@ViewerActivity, getString(R.string.viewer_delete_block_bookmarked), Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle(R.string.viewer_delete_current_title)
                .setMessage(R.string.viewer_delete_current_message)
                .setPositiveButton(R.string.common_delete) { _, _ ->
                    lifecycleScope.launch {
                        val deleted = BookFileManager.deleteCurrentBook(folder)
                        if (!deleted) {
                            Toast.makeText(this@ViewerActivity, getString(R.string.viewer_delete_failed), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        navigateToLibraryAfterDelete()
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }
    private fun deleteAllBooks() {
        AlertDialog.Builder(this@ViewerActivity)
            .setTitle(R.string.viewer_delete_all_title)
            .setMessage(R.string.viewer_delete_all_first_confirm)
            .setPositiveButton(R.string.common_continue) { _, _ ->
                val input = EditText(this@ViewerActivity).apply {
                    hint = getString(R.string.viewer_delete_all_keyword)
                }
                AlertDialog.Builder(this@ViewerActivity)
                    .setTitle(R.string.viewer_delete_all_title)
                    .setMessage(R.string.viewer_delete_all_second_confirm)
                    .setView(input)
                    .setPositiveButton(R.string.common_delete) { _, _ ->
                        if (input.text?.toString()?.trim() != getString(R.string.viewer_delete_all_keyword)) {
                            Toast.makeText(this@ViewerActivity, getString(R.string.viewer_delete_all_keyword_mismatch), Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        lifecycleScope.launch {
                            val deleted = BookFileManager.deleteAllBooks(File(filesDir, "Books"), keepBookmarked = false)
                            TextViewerSettingsStore.clearAllBookPositions(this@ViewerActivity)
                            ImageDataHolder.clear()
                            Toast.makeText(this@ViewerActivity, getString(R.string.viewer_delete_all_done, deleted), Toast.LENGTH_SHORT).show()
                            navigateToLibraryAfterDelete()
                        }
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
    private fun updatePageIndicator() {
        val total = currentUris.size
        val position = getCurrentPosition()
        tvPageIndicator.text = getString(R.string.viewer_page_indicator_format, position + 1, total)
    }

    private fun toggleControls() {
        isControlsVisible = !isControlsVisible

        // [蹂寃? 泥쒖쿇???щ씪吏?꾨줉 ?좊땲硫붿씠???띾룄 議곗젅 (500ms)
        val duration = 500L

        if (isControlsVisible) {
            layoutTopBar.visibility = View.VISIBLE
            layoutBottomBar.visibility = View.VISIBLE

            layoutTopBar.animate()
                .alpha(1f)
                .setDuration(duration)
                .start()

            layoutBottomBar.animate()
                .alpha(1f)
                .setDuration(duration)
                .start()
        } else {
            layoutTopBar.animate()
                .alpha(0f)
                .setDuration(duration)
                .withEndAction {
                    layoutTopBar.visibility = View.GONE
                }
                .start()

            layoutBottomBar.animate()
                .alpha(0f)
                .setDuration(duration)
                .withEndAction {
                    layoutBottomBar.visibility = View.GONE
                }
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        startFileMonitor()
    }

    override fun onPause() {
        super.onPause()
        stopFileMonitor()
        saveCurrentProgress()
    }

    private fun startFileMonitor() {
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            while (isActive) {
                checkCurrentPageUpdate()
                delay(1000)
            }
        }
    }

    private fun stopFileMonitor() {
        monitorJob?.cancel()
    }

    private fun checkCurrentPageUpdate() {
        val currentPos = getCurrentPosition()
        val uri = currentUris.getOrNull(currentPos) ?: return
        val file = File(uri.path ?: "")

        if (file.exists()) {
            val currentMod = file.lastModified()
            val lastKnownMod = pageTimestamps[currentPos]

            if (lastKnownMod == null) {
                pageTimestamps[currentPos] = currentMod
            } else if (currentMod > lastKnownMod) {
                pageTimestamps[currentPos] = currentMod
                adapter.notifyItemChanged(currentPos)
            }
        }
    }

    private fun saveCurrentProgress() {
        if (bookId.isEmpty()) return

        val currentPage = getCurrentPosition()
        val totalPages = currentUris.size

        if (totalPages > 0) {
            lifecycleScope.launch {
                repository.saveProgress(bookId, currentPage, totalPages)
            }
        }
    }


    private fun checkExportPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        exportCurrentBook()
    }

    private fun exportCurrentBook() {
        val folder = currentBookFolder
        if (folder == null || !folder.exists()) {
            Toast.makeText(this, getString(R.string.settings_no_books_to_export), Toast.LENGTH_SHORT).show()
            return
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_exporting_title)
            .setMessage(R.string.settings_exporting_message)
            .setCancelable(false)
            .create()
        progressDialog.show()
        lifecycleScope.launch {
            val result = BookFileManager.exportCurrentBook(this@ViewerActivity, folder)
            progressDialog.dismiss()
            if (result.successCount > 0) {
                Toast.makeText(
                    this@ViewerActivity,
                    getString(R.string.settings_export_success_format, result.successCount),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@ViewerActivity, getString(R.string.settings_no_images_to_export), Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun navigateToLibraryAfterDelete() {
        ImageDataHolder.clear()
        startActivity(Intent(this, LibraryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }
inner class ImageSliderAdapter(private val uris: MutableList<Uri>) :
        RecyclerView.Adapter<ImageSliderAdapter.SliderViewHolder>() {

        inner class SliderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val photoView: PhotoView = view.findViewById(R.id.photo_view)
            val progressBar: ProgressBar = view.findViewById(R.id.progress_bar)
            val tvError: TextView = view.findViewById(R.id.tv_error)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SliderViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_viewer_page, parent, false)

            // [寃? 諛뺤뒪 ?쒓굅 諛??뱁댆 紐⑤뱶 濡쒕뵫 理쒖쟻??
            if (isVerticalMode) {
                // [以묒슂] 珥덇린 ?믪씠瑜?0???꾨땶 媛??붾㈃ ?믪씠???덈컲 ?뺣룄)?쇰줈 媛뺤젣 ?ㅼ젙
                val screenHeight = parent.resources.displayMetrics.heightPixels
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                view.minimumHeight = screenHeight / 2 // 理쒖냼 ?믪씠 吏??
            } else {
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                view.minimumHeight = 0 // 珥덇린??
            }

            return SliderViewHolder(view)
        }

        override fun onBindViewHolder(holder: SliderViewHolder, position: Int) {
            val uri = uris[position]

            holder.progressBar.visibility = View.VISIBLE
            holder.tvError.visibility = View.GONE

            holder.photoView.maximumScale = 5.0f

            // [?듭떖] 寃? ?щ갚 ?쒓굅 濡쒖쭅
            if (isVerticalMode) {
                // ?뱁댆 紐⑤뱶: 媛濡?苑?梨꾩슦湲?+ ?몃줈 鍮꾩쑉 ?먮룞 議곗젅 (?щ갚 ?쒓굅)
                holder.photoView.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.photoView.adjustViewBounds = true
            } else {
                // ?쇰컲 紐⑤뱶: ?붾㈃ 以묒븰 ?뺣젹 (?섏씠吏 ?섍?)
                holder.photoView.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.photoView.adjustViewBounds = false
            }

            // [UX 蹂寃? 紐⑤뱶???곕씪 議곗옉 諛⑹떇 遺꾧린
            if (isVerticalMode) {
                // ?뱁댆 紐⑤뱶: ?붾㈃ ?곗튂 由ъ뒪???쒓굅 (??踰꾪듉?쇰줈留?議곗옉)
                holder.photoView.setOnClickListener(null)
                holder.photoView.setOnViewTapListener(null)
            } else {
                // ?쇰컲 紐⑤뱶: ?붾㈃ ?곗튂 ???곹븯?⑤컮 ?좉? (湲곗〈 諛⑹떇 蹂듦뎄)
                holder.photoView.setOnClickListener { toggleControls() }
                holder.photoView.setOnViewTapListener { _, _, _ -> toggleControls() }
            }

            val file = File(uri.path ?: "")
            val lastModified = if (file.exists()) file.lastModified() else System.currentTimeMillis()

            pageTimestamps[position] = lastModified

            // [珥덇퀬??濡쒕뵫 理쒖쟻??
            Glide.with(holder.itemView.context)
                .load(uri)
                .signature(ObjectKey(lastModified))
                .override(screenWidth, Target.SIZE_ORIGINAL) // ?붾㈃ ?덈퉬??留욎떠 由ъ궗?댁쭠
                .format(DecodeFormat.PREFER_RGB_565) // 硫붾え由??덉빟 ?щ㎎
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        holder.progressBar.visibility = View.GONE
                        holder.tvError.visibility = View.VISIBLE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        holder.progressBar.visibility = View.GONE
                        holder.tvError.visibility = View.GONE
                        return false
                    }
                })
                .into(holder.photoView)
        }

        override fun getItemCount() = uris.size
    }
}







