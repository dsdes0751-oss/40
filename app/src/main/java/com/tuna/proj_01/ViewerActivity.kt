package com.tuna.proj_01

import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ViewerActivity : AppCompatActivity() {
    private val exportPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            exportCurrentBook()
        } else {
            Toast.makeText(this, getString(R.string.settings_storage_permission_required), Toast.LENGTH_SHORT).show()
        }
    }


    // [변경] ViewPager2 대신 RecyclerView를 메인 뷰어로 사용 (웹툰 모드 지원을 위해)
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

    // [추가] 가시성 토글 버튼 (전역 변수로 승격)
    private lateinit var btnVisibilityToggle: ImageButton

    // 현재 책의 폴더 경로 및 ID
    private var currentBookFolder: File? = null
    private var bookId: String = ""

    private val pageTimestamps = mutableMapOf<Int, Long>()
    private var monitorJob: Job? = null

    // [설정 상태]
    private var isVolumeKeyNavigationEnabled = false
    private var isVerticalMode = false // 웹툰 모드 (세로 스크롤)
    private var isShowOriginal = false // 원본 보기 (추후 구현)

    // 스냅 헬퍼 (페이지 모드용)
    private var snapHelper: PagerSnapHelper? = null

    // [최적화] 화면 너비 미리 계산
    private var screenWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_viewer)

        // [최적화] 화면 너비 구하기 (Glide 리사이징용)
        screenWidth = resources.displayMetrics.widthPixels

        repository = BookRepository(applicationContext)

        // 설정 불러오기
        val prefs = getSharedPreferences("ViewerSettings", Context.MODE_PRIVATE)
        isVolumeKeyNavigationEnabled = prefs.getBoolean("VolumeKeyNav", false)
        isVerticalMode = prefs.getBoolean("VerticalMode", false)
        isShowOriginal = prefs.getBoolean("ShowOriginal", false)

        // [핵심 변경] XML에 있는 ViewPager2를 찾아서 부모로부터 제거하고, 그 자리에 RecyclerView를 넣습니다.
        replaceViewPagerWithRecyclerView()

        layoutTopBar = findViewById(R.id.layout_top_bar)
        layoutBottomBar = findViewById(R.id.layout_bottom_bar)
        tvPageIndicator = findViewById(R.id.tv_page_indicator)
        tvTitle = findViewById(R.id.tv_title)

        val btnClose = findViewById<ImageButton>(R.id.btn_close)
        val btnSettings = findViewById<ImageButton>(R.id.btn_settings)
        // [기능 추가] 가시성 토글 버튼 연결
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

        // 시작 위치 가져오기
        val startPosition = intent.getIntExtra("start_position", 0)

        // [최적화] 뷰어 모드 설정 시 startPosition을 함께 전달하여
        // 레이아웃 매니저가 처음부터 해당 위치를 그리도록 함 (0번부터 그리는 오버헤드 제거)
        setupViewerMode(startPosition)
        updatePageIndicator()

        btnClose.setOnClickListener { finish() }

        // [설정] Viewer settings 다이얼로그 호출
        btnSettings.setOnClickListener { showViewerSettingsDialog() }

        // [기능 추가] 가시성 버튼 클릭 시 상하단바 토글
        btnVisibilityToggle.setOnClickListener {
            toggleControls()
        }

        // 스크롤 리스너 (페이지 인디케이터 업데이트)
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
            id = R.id.view_pager // ID 유지
            setBackgroundColor(Color.BLACK)
            // [최적화] 캐시 사이즈 조절 (너무 크면 메모리 부족, 적당히 설정)
            setItemViewCacheSize(3)
        }
        parent.addView(viewerRecyclerView, index)
    }

    /**
     * [최적화] LayoutManager 설정 시 초기 위치를 지정하여 불필요한 로딩 방지
     */
    private fun setupViewerMode(initialPosition: Int = -1) {
        val targetPos = if (initialPosition >= 0) initialPosition else getCurrentPosition()

        if (isVerticalMode) {
            // [웹툰 모드] 세로 스크롤, 스냅 없음
            val lm = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
            lm.scrollToPositionWithOffset(targetPos, 0) // 오프셋 0으로 정확히 이동
            viewerRecyclerView.layoutManager = lm
            snapHelper?.attachToRecyclerView(null) // 스냅 해제

            // [UX 변경] 웹툰 모드에서는 눈 버튼 보이기
            btnVisibilityToggle.visibility = View.VISIBLE
        } else {
            // [일반 모드] 가로 스크롤, 페이지 단위 스냅
            val lm = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            lm.scrollToPosition(targetPos)
            viewerRecyclerView.layoutManager = lm

            if (snapHelper == null) snapHelper = PagerSnapHelper()
            snapHelper?.attachToRecyclerView(viewerRecyclerView)

            // [UX 변경] 일반 모드에서는 눈 버튼 숨기기 (터치로 조작)
            btnVisibilityToggle.visibility = View.GONE
        }
        // 모드 변경 시 어댑터 갱신 (아이템 레이아웃 변경 적용을 위해)
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

    // [기능 수정] XML 레이아웃(dialog_viewer_settings.xml)을 사용하여 설정창 띄우기
    private fun showViewerSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_viewer_settings, null)

        // 1. 뷰 바인딩
        val switchVertical = dialogView.findViewById<SwitchMaterial>(R.id.switch_vertical_mode)
        val switchOriginal = dialogView.findViewById<SwitchMaterial>(R.id.switch_show_original)
        val switchVolume = dialogView.findViewById<SwitchMaterial>(R.id.switch_volume)

        // [추가] 표지 숨김 스위치
        val switchHideCurrent = dialogView.findViewById<SwitchMaterial>(R.id.switch_hide_current_cover)
        val switchHideAll = dialogView.findViewById<SwitchMaterial>(R.id.switch_hide_all_covers)

                val btnExportCurrent = dialogView.findViewById<Button>(R.id.btn_export_current)
val btnDeleteCurrent = dialogView.findViewById<Button>(R.id.btn_delete_current)
        val btnDeleteAll = dialogView.findViewById<Button>(R.id.btn_delete_all)

        // 2. 현재 상태 반영
        switchVertical.isChecked = isVerticalMode
        switchOriginal.isChecked = isShowOriginal
        switchVolume.isChecked = isVolumeKeyNavigationEnabled

        // [추가] 표지 설정 상태 로드
        val prefs = getSharedPreferences("ViewerSettings", Context.MODE_PRIVATE)
        switchHideAll.isChecked = prefs.getBoolean("HideAllCovers", false)

        // 현재 책 상태 로드 (비동기)
        if (currentBookFolder != null) {
            lifecycleScope.launch {
                val metadata = withContext(Dispatchers.IO) {
                    BookMetadataManager.loadMetadata(currentBookFolder!!)
                }
                switchHideCurrent.isChecked = metadata.isCoverHidden
            }
        }

        // 3. 다이얼로그 생성

        if (currentBookFolder == null || !currentBookFolder!!.exists()) {
            btnExportCurrent.visibility = View.GONE
        }
val dialog = AlertDialog.Builder(this)
            .setTitle("Viewer settings")
            .setView(dialogView)
            .setPositiveButton("Close") { _, _ ->
                // 설정값 저장 (UI 반영은 리스너에서 즉시 처리됨)
                saveViewerSettings()
            }
            .create()

        // 4. 리스너 설정

        // [웹툰 모드] 즉시 반영
        switchVertical.setOnCheckedChangeListener { _, isChecked ->
            isVerticalMode = isChecked
            setupViewerMode() // 현재 위치 유지하며 모드 변경
            saveViewerSettings()
        }

        // [원본 보기]
        switchOriginal.setOnCheckedChangeListener { _, isChecked ->
            isShowOriginal = isChecked
            saveViewerSettings()
            if (isChecked) {
                Toast.makeText(this, "현재 버전에서는 원본 파일이 따로 저장되지 않습니다.\n(추후 업데이트 예정)", Toast.LENGTH_SHORT).show()
            }
        }

        // [볼륨키 조작]
        switchVolume.setOnCheckedChangeListener { _, isChecked ->
            isVolumeKeyNavigationEnabled = isChecked
            saveViewerSettings()
        }

        // [추가] 표지 숨김 리스너
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


        btnExportCurrent.setOnClickListener {
            dialog.dismiss()
            checkExportPermission()
        }
btnDeleteCurrent.setOnClickListener {
            deleteCurrentBook()
        }

        btnDeleteAll.setOnClickListener {
            deleteAllBooks()
        }

        dialog.show()
    }

    private fun saveViewerSettings() {
        getSharedPreferences("ViewerSettings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("VolumeKeyNav", isVolumeKeyNavigationEnabled)
            .putBoolean("VerticalMode", isVerticalMode)
            .putBoolean("ShowOriginal", isShowOriginal)
            .apply()
    }

    // [기능 추가] 볼륨키 이벤트 가로채기
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKeyNavigationEnabled) {
            val current = getCurrentPosition()

            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    // 이전 페이지
                    if (current > 0) {
                        if (isVerticalMode) {
                            // 웹툰 모드에서는 부드럽게 스크롤
                            viewerRecyclerView.smoothScrollBy(0, -viewerRecyclerView.height / 2)
                        } else {
                            viewerRecyclerView.smoothScrollToPosition(current - 1)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // 다음 페이지
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
        if (currentBookFolder != null) {
            lifecycleScope.launch {
                val metadata = withContext(Dispatchers.IO) {
                    BookMetadataManager.loadMetadata(currentBookFolder!!)
                }
                if (metadata.isBookmarked) {
                    Toast.makeText(this@ViewerActivity, "Remove bookmark before deleting.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                AlertDialog.Builder(this@ViewerActivity)
                    .setTitle("Delete confirmation")
                    .setMessage("Delete the currently opened book?")
                    .setPositiveButton("Delete") { _, _ ->
                        if (currentBookFolder != null && currentBookFolder!!.exists()) {
                            currentBookFolder!!.deleteRecursively()
                            Toast.makeText(this@ViewerActivity, "책이 Delete되었습니다.", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // [기능 변경] 북마크되지 않은 모든 책 Delete
    private fun deleteAllBooks() {
        AlertDialog.Builder(this)
            .setTitle("전체 Delete 경고")
            .setMessage("서재에 있는 '북마크되지 않은 모든 책'이 Delete됩니다.\n정말 초기화하시겠습니까?")
            .setPositiveButton("모두 Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val rootDir = File(filesDir, "Books")
                        if (rootDir.exists()) {
                            val folders = rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                            for (folder in folders) {
                                val metadata = BookMetadataManager.loadMetadata(folder)
                                if (!metadata.isBookmarked) {
                                    folder.deleteRecursively()
                                }
                            }
                        }
                    }
                    Toast.makeText(this@ViewerActivity, "북마크되지 않은 모든 책이 Delete되었습니다.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePageIndicator() {
        val total = currentUris.size
        val position = getCurrentPosition()
        tvPageIndicator.text = "${position + 1} / $total"
    }

    private fun toggleControls() {
        isControlsVisible = !isControlsVisible

        // [변경] 천천히 사라지도록 애니메이션 속도 조절 (500ms)
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
        if (currentBookFolder == null || !currentBookFolder!!.exists()) {
            Toast.makeText(this, getString(R.string.settings_no_books_to_export), Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_exporting_title)
            .setMessage(R.string.settings_exporting_message)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val bookName = currentBookFolder!!.name
            // Sanitize name
            val safeName = bookName.replace(Regex("[^a-zA-Z0-9가-힣_\\- ]"), "").trim()
            val targetName = if (safeName.isNotEmpty()) safeName else bookName

            val images = currentBookFolder!!.listFiles { f ->
                val n = f.name.lowercase()
                n.endsWith(".jpg") || n.endsWith(".png")
            }?.sortedBy { it.name } ?: emptyList()

            var successCount = 0
            for (image in images) {
                if (ExportHelper.saveFileToDownloads(
                        this@ViewerActivity,
                        image,
                        "Manga/",
                        image.name,
                        "image/jpeg"
                    )
                ) {
                    successCount++
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (successCount > 0) {
                    Toast.makeText(this@ViewerActivity, getString(R.string.settings_export_success_format, successCount), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ViewerActivity, getString(R.string.settings_no_images_to_export), Toast.LENGTH_SHORT).show()
                }
            }
        }
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

            // [검은 박스 제거 및 웹툰 모드 로딩 최적화]
            if (isVerticalMode) {
                // [중요] 초기 높이를 0이 아닌 값(화면 높이의 절반 정도)으로 강제 설정
                val screenHeight = parent.resources.displayMetrics.heightPixels
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                view.minimumHeight = screenHeight / 2 // 최소 높이 지정
            } else {
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                view.minimumHeight = 0 // 초기화
            }

            return SliderViewHolder(view)
        }

        override fun onBindViewHolder(holder: SliderViewHolder, position: Int) {
            val uri = uris[position]

            holder.progressBar.visibility = View.VISIBLE
            holder.tvError.visibility = View.GONE

            holder.photoView.maximumScale = 5.0f

            // [핵심] 검은 여백 제거 로직
            if (isVerticalMode) {
                // 웹툰 모드: 가로 꽉 채우기 + 세로 비율 자동 조절 (여백 제거)
                holder.photoView.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.photoView.adjustViewBounds = true
            } else {
                // 일반 모드: 화면 중앙 정렬 (페이지 넘김)
                holder.photoView.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.photoView.adjustViewBounds = false
            }

            // [UX 변경] 모드에 따라 조작 방식 분기
            if (isVerticalMode) {
                // 웹툰 모드: 화면 터치 리스너 제거 (눈 버튼으로만 조작)
                holder.photoView.setOnClickListener(null)
                holder.photoView.setOnViewTapListener(null)
            } else {
                // 일반 모드: 화면 터치 시 상하단바 토글 (기존 방식 복구)
                holder.photoView.setOnClickListener { toggleControls() }
                holder.photoView.setOnViewTapListener { _, _, _ -> toggleControls() }
            }

            val file = File(uri.path ?: "")
            val lastModified = if (file.exists()) file.lastModified() else System.currentTimeMillis()

            pageTimestamps[position] = lastModified

            // [초고속 로딩 최적화]
            Glide.with(holder.itemView.context)
                .load(uri)
                .signature(ObjectKey(lastModified))
                .override(screenWidth, Target.SIZE_ORIGINAL) // 화면 너비에 맞춰 리사이징
                .format(DecodeFormat.PREFER_RGB_565) // 메모리 절약 포맷
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
