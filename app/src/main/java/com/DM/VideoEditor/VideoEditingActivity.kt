package com.DM.VideoEditor

import android.annotation.SuppressLint
import androidx.appcompat.app.AlertDialog
import android.content.ContentResolver
import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.DM.VideoEditor.databinding.ActivityVideoEditingBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import java.io.File
import java.util.Collections
import java.util.Locale
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.viewModels
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import kotlinx.parcelize.Parcelize
import android.os.Parcelable

// ── Data Models ──────────────────────────────────────────────

@Parcelize
data class VideoClip(
    var uri: Uri,
    var durationMs: Long = 0L,
    var startTrimMs: Long = 0L,
    var endTrimMs: Long = 0L,
    var transition: String = "none",
    var speedFactor: Float = 1.0f,
    var filterCmd: String = "",
    var volume: Float = 1.0f,
    var audioFadeInMs: Long = 0L,
    var audioFadeOutMs: Long = 0L,
    var chromaKeyColor: String? = null, // e.g. "00FF00"
    var chromaSimilarity: Float = 0.1f,
    var chromaSmoothness: Float = 0.05f
) : Parcelable

@Parcelize
data class TextOverlay(
    val id: Long = System.currentTimeMillis(),
    var text: String,
    var fontSize: Int = 36,
    var color: String = "white",
    var position: String = "Bottom Center",  // kept for legacy export fallback
    var startSec: Float = 0f,
    var endSec: Float = -1f,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var bgAlpha: Float = 0.4f,
    var shadow: Boolean = true,
    // Draggable position (0..1 normalized relative to video frame)
    var normalizedX: Float = 0.5f,
    var normalizedY: Float = 0.8f,
    var textScale: Float = 1.0f,
    var textRotation: Float = 0f,
    var animationType: String = "none" // none, slide_in, zoom_fade, typewriter
) : Parcelable



// ── Main Activity ─────────────────────────────────────────────

class VideoEditingActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityVideoEditingBinding
    internal lateinit var player: ExoPlayer

    internal val clips = mutableListOf<VideoClip>()
    internal val textOverlays = mutableListOf<TextOverlay>()
    internal var selectedClipIndex = 0
    internal var isVideoLoaded = false
    internal var progressUpdateJob: Job? = null

    // ── Draft / Auto-Save ─────────────────────────────────────
    internal var currentDraftId: String? = null
    internal var autosaveJob: Job? = null

    internal lateinit var clipAdapter: ClipAdapter
    internal lateinit var textAdapter: TextOverlayAdapter

    // Multi-track timeline
    internal lateinit var multiTrackTimeline: com.DM.VideoEditor.customviews.MultiTrackTimelineView
    internal fun isTimelineInitialized() = this::multiTrackTimeline.isInitialized

    internal val editorViewModel: VideoEditorViewModel by viewModels()
    internal val undoRedo get() = editorViewModel.undoRedo

    internal val thumbnailCache = mutableMapOf<Int, android.graphics.Bitmap>()
    internal val pendingVideoTrimStartRedo = longArrayOf(0L)
    internal val pendingVideoTrimEndRedo = longArrayOf(0L)
    internal val pendingTextMoveRedo = floatArrayOf(0f, 0f)
    internal val pendingTextTrimStartRedo = floatArrayOf(0f)
    internal val pendingTextTrimEndRedo = floatArrayOf(0f)
    internal fun editorColor(@ColorRes id: Int) = ContextCompat.getColor(this, id)
    internal fun swapThumbnailCacheKeys(i: Int, j: Int) {
        if (i == j) return
        val bi = thumbnailCache.remove(i)
        val bj = thumbnailCache.remove(j)
        bi?.let { thumbnailCache[j] = it }
        bj?.let { thumbnailCache[i] = it }
    }

    // Category toolbar state
    internal var currentCategory = "edit"

    // PIP state
    internal var pipPosition = 0 // 0=TR, 1=TL, 2=BR, 3=BL, 4=Center

    // Voiceover recorder
    internal var mediaRecorder: MediaRecorder? = null
    internal var voiceoverFile: File? = null
    internal var isRecording = false

    // dp extension
    internal val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // Launchers
    internal val addVideoLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { addClip(it) }
    }
    internal val audioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { addAudioTrack(it) }
    }

    // REQ-6: track last added audio URI for timeline visualization
    internal var projectAudioUri: Uri? = null
    internal var projectAudioDurationMs: Long = 0L
    internal val pipVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { showPipPositionPicker(it) }
    }
    internal val replaceClipLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { replaceCurrentClip(it) }
    }

    private val exportFinishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ExportForegroundService.ACTION_EXPORT_FINISHED) return
            if (isDestroyed) return
            val ok = intent.getBooleanExtra(ExportForegroundService.EXTRA_SUCCESS, false)
            if (ok) {
                val path = intent.getStringExtra(ExportForegroundService.EXTRA_OUTPUT_PATH) ?: return
                val file = File(path)
                if (!file.exists()) return
                val dur = intent.getLongExtra(ExportForegroundService.EXTRA_DURATION_MS, 0L)
                val savedUriStr = intent.getStringExtra(ExportForegroundService.EXTRA_OUTPUT_URI)
                val textCount = intent.getIntExtra(ExportForegroundService.EXTRA_TEXT_OVERLAY_COUNT, 0)
                clips.clear()
                clips.add(VideoClip(uri = Uri.fromFile(file), durationMs = dur))
                clipAdapter.notifyDataSetChanged()
                clipAdapter.setSelected(0)
                playClip(0)
                updateClipCountBadge()
                rebuildTimeline()
                val sizeMB = (file.length() / 1024 / 1024).toInt().coerceAtLeast(1)
                MaterialAlertDialogBuilder(this@VideoEditingActivity)
                    .setTitle(R.string.export_success_title)
                    .setMessage(getString(R.string.export_success_message, sizeMB, textCount))
                    .setPositiveButton(R.string.export_share_video) { _, _ ->
                        savedUriStr?.let { s ->
                            val shareUri = Uri.parse(s)
                            startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    getString(R.string.export_share_chooser)
                                )
                            )
                        }
                    }
                    .setNegativeButton(R.string.export_ok) { _, _ -> }
                    .show()
            } else {
                val err = intent.getStringExtra(ExportForegroundService.EXTRA_ERROR_MESSAGE).orEmpty()
                if (err.isNotBlank()) showSnack(getString(R.string.export_error_generic, err))
                else showSnack(getString(R.string.export_err_failed))
            }
        }
    }

    // ── onCreate ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityVideoEditingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge()
        setupPlayer()
        setupClipTimeline()
        setupTextPanel()
        setupCategoryToolbar()
        setupUndoRedo()
        setupMultiTrackTimeline()
        EditorCacheUtils.cleanOldFfinFiles(cacheDir)
        EditorCacheUtils.enforceFfinCacheBudget(cacheDir)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exportFinishedReceiver, IntentFilter(ExportForegroundService.ACTION_EXPORT_FINISHED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(exportFinishedReceiver, IntentFilter(ExportForegroundService.ACTION_EXPORT_FINISHED))
        }

        // ── Resume from a saved draft ──────────────────────────
        val draftId = intent.getStringExtra("DRAFT_ID")
        if (draftId != null) {
            currentDraftId = draftId
            val draft = ProjectDraftManager.load(this, draftId)
            if (draft != null) {
                lifecycleScope.launch {
                    clips.clear()
                    clips.addAll(draft.clips)
                    textOverlays.clear()
                    textOverlays.addAll(draft.textOverlays)
                    draft.projectAudioUri?.let { projectAudioUri = it }
                    withContext(Dispatchers.IO) {
                        clips.forEach { clip ->
                            if (clip.durationMs <= 0L) {
                                clip.durationMs = getClipDurationMs(clip.uri)
                            }
                        }
                    }
                    clipAdapter.notifyDataSetChanged()
                    updateTextBadge()
                    bumpOverlaysVersion()
                    updateClipCountBadge()
                    rebuildTimeline()
                    loadThumbsForTimeline()
                    loadThumbsStaggered(clips.indices.toList())
                    if (clips.isNotEmpty()) {
                        selectedClipIndex = 0
                        clipAdapter.setSelected(0)
                        player.pause()
                        playClip(0)
                    }
                    showSnack(getString(R.string.draft_restored))
                }
                return
            }
        }
        applyStartupIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = VideoImportIntentParser.extractMediaUris(intent)
        if (incoming.isNotEmpty()) promptImportOrReplace(incoming)
    }

    /**
     * Priority: external share ([Intent.ACTION_SEND] / _MULTIPLE) → in-app [VIDEO_URIS] → single [VIDEO_URI].
     */
    internal fun applyStartupIntent(intent: Intent) {
        val shareUris = VideoImportIntentParser.extractMediaUris(intent)
        if (shareUris.isNotEmpty()) {
            importInitialClips(shareUris)
            return
        }
        val extraUris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("VIDEO_URIS", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("VIDEO_URIS")
        }
        if (!extraUris.isNullOrEmpty()) {
            importInitialClips(extraUris)
            return
        }
        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra("VIDEO_URI", Uri::class.java)
        else intent.getParcelableExtra("VIDEO_URI")
        uri?.let { addClip(it) }
    }
    internal fun promptImportOrReplace(newUris: List<Uri>) {
        if (newUris.isEmpty()) return
        if (clips.isEmpty()) {
            importInitialClips(newUris)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.replace_project_title)
            .setMessage(R.string.replace_project_message)
            .setPositiveButton(R.string.replace_project_confirm) { _, _ ->
                textOverlays.clear()
                projectAudioUri = null
                thumbnailCache.values.forEach { if (!it.isRecycled) it.recycle() }
                thumbnailCache.clear()
                if (::clipAdapter.isInitialized) clipAdapter.recycleAllStripThumbnails()
                clips.clear()
                clipAdapter.notifyDataSetChanged()
                currentDraftId = null
                undoRedo.clear()
                importInitialClips(newUris)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        autosaveJob = lifecycleScope.launch {
            while (isActive) {
                delay(45_000)
                withContext(NonCancellable) { saveDraftSnapshot() }
            }
        }
    }

    override fun onStop() {
        autosaveJob?.cancel()
        autosaveJob = null
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            saveDraftSnapshot()
        }
    }

    /** Persists project; safe to call from background coroutines with [NonCancellable]. */
    internal fun saveDraftSnapshot() {
        if (clips.isEmpty()) return
        try {
            currentDraftId = ProjectDraftManager.save(
                ctx             = this,
                draftId         = currentDraftId,
                clips           = clips,
                textOverlays    = textOverlays,
                projectAudioUri = projectAudioUri
            )
        } catch (e: Exception) {
            Log.e("VideoEditor", "saveDraftSnapshot failed", e)
        }
    }

    // ── Auto-Save on pause (user leaves / presses home) ───────
    override fun onPause() {
        super.onPause()
        saveDraftSnapshot()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(exportFinishedReceiver)
        } catch (_: IllegalArgumentException) { }
        super.onDestroy()
        FfmpegExecutor.cancel()
        player.release()
        progressUpdateJob?.cancel()
        try { mediaRecorder?.apply { stop(); release() }; mediaRecorder = null } catch (_: Exception) {}
        if (::clipAdapter.isInitialized) clipAdapter.recycleAllStripThumbnails()
        thumbnailCache.values.forEach { if (!it.isRecycled) it.recycle() }
        thumbnailCache.clear()
        EditorCacheUtils.deleteIntermediateFiles(cacheDir)
    }

    // ── Edge-to-edge (Android 15 / API 35–safe): no hiding system bars ───────
    internal fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.systemBars())
        }
        EdgeToEdgeInsets.applyTopBarInsets(binding.toolbar)
        EdgeToEdgeInsets.applyHorizontalInsets(binding.subToolsScroll)
        EdgeToEdgeInsets.applyBottomBarInsets(binding.categoryBarScroll)
        EdgeToEdgeInsets.applyHorizontalInsets(binding.playerView)
        EdgeToEdgeInsets.applyHorizontalInsets(binding.textPreviewContainer)
        EdgeToEdgeInsets.applyHorizontalInsets(binding.playControlBar)
        EdgeToEdgeInsets.applyHorizontalInsets(binding.multiTrackTimeline)
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    }

    // ── Player Setup ──────────────────────────────────────────

    @OptIn(UnstableApi::class)
    internal fun setupPlayer() {
        val rf = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 48_000, 1_500, 4_000)
            .build()
        player = ExoPlayer.Builder(this, rf)
            .setLoadControl(loadControl)
            .build()
        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        player.repeatMode = Player.REPEAT_MODE_OFF // Constraint 4
        binding.playerView.player = player
        binding.playerView.useController = false

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Update UI when playback naturally crosses into the next clip
                val newIdx = player.currentMediaItemIndex
                if (newIdx in clips.indices && newIdx != selectedClipIndex) {
                    selectedClipIndex = newIdx
                    clipAdapter.setSelected(newIdx)
                    binding.tvVideoName.text = clips[newIdx].uri.lastPathSegment?.substringAfterLast("/") ?: "Video ${newIdx + 1}"
                    if (::multiTrackTimeline.isInitialized) {
                        multiTrackTimeline.selectVideoBlock(newIdx)
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !isVideoLoaded) {
                    isVideoLoaded = true
                    binding.loadingScreen.root.visibility = View.GONE
                    val totalDur = clips.sumOf { effectiveDurationMs(it) }
                    val currentGlobalPos = getClipCumulativeStartMs(player.currentMediaItemIndex) + player.currentPosition
                    binding.tvTotalTime.text = fmtMs(totalDur)
                    binding.tvDuration.text = "${fmtMs(currentGlobalPos)} / ${fmtMs(totalDur)}"
                    startProgressUpdater()
                    loadThumbsForTimeline()
                }
                updatePlayPause()
            }
            override fun onIsPlayingChanged(playing: Boolean) = updatePlayPause()

            override fun onPlayerError(error: PlaybackException) {
                Log.w("DmEditor", "Player error: ${error.message}")
                val clip = clips.getOrNull(selectedClipIndex) ?: return
                lifecycleScope.launch {
                    showSnack(getString(R.string.player_error_loading))
                    val transcoded = transcodeForPlayback(clip)
                    if (transcoded != null) {
                        clips[selectedClipIndex] = clip.copy(uri = Uri.fromFile(transcoded))
                        reloadPlaylist(maintainPosition = false, targetIdx = selectedClipIndex)
                    } else {
                        binding.loadingScreen.root.visibility = View.GONE
                        showSnack(getString(R.string.player_error_cannot_play))
                    }
                }
            }
        })

        binding.btnPlayPause.setOnClickListener { if (player.isPlaying) player.pause() else player.play() }
        binding.btnSave.setOnClickListener { showExportQualityDialog() }
        binding.btnHome.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // customVideoSeeker is now hidden; seeking is handled by MultiTrackTimelineView
    }

    // ── Clip Timeline ─────────────────────────────────────────


    // ── Text Panel ────────────────────────────────────────────
internal fun setupTextPanel() {
        textAdapter = TextOverlayAdapter(textOverlays,
            onEdit = { showTextSheet(it) },
            onDelete = { idx ->
                val removed = textOverlays[idx]
                val delIdx = idx
                textOverlays.removeAt(delIdx)
                textAdapter.notifyDataSetChanged()
                updateTextBadge(); bumpOverlaysVersion(); rebuildTimeline()
                undoRedo.commit(
                    undo = {
                        textOverlays.add(delIdx.coerceAtMost(textOverlays.size), removed)
                        textAdapter.notifyDataSetChanged()
                        updateTextBadge(); bumpOverlaysVersion(); rebuildTimeline()
                    },
                    redo = {
                        if (delIdx in textOverlays.indices) textOverlays.removeAt(delIdx)
                        textAdapter.notifyDataSetChanged()
                        updateTextBadge(); bumpOverlaysVersion(); rebuildTimeline()
                    }
                )
            }
        )
        binding.rvTextOverlays.layoutManager = LinearLayoutManager(this)
        binding.rvTextOverlays.adapter = textAdapter
    }

    // ── Filmora Category Toolbar ──────────────────────────────
internal fun setupCategoryToolbar() {
        binding.catEdit.setOnClickListener    { selectCategory("edit") }
        binding.catAudio.setOnClickListener   { selectCategory("audio") }
        binding.catText.setOnClickListener    { selectCategory("text") }
        binding.catSticker.setOnClickListener { selectCategory("sticker") }
        binding.catEffects.setOnClickListener { selectCategory("effects") }
        binding.catFilter.setOnClickListener  { selectCategory("filter") }
        binding.catAdjust.setOnClickListener  { selectCategory("adjust") }
        binding.catCanvas.setOnClickListener  { selectCategory("canvas") }
        binding.btnAddTextOverlay.setOnClickListener { showTextSheet(null) }
        selectCategory("edit")
    }
internal fun selectCategory(cat: String) {
        currentCategory = cat
        updateCategoryHighlight(cat)
        populateSubTools(cat)
    }
internal fun updateCategoryHighlight(activeCat: String) {
        val cats = listOf("edit", "audio", "text", "sticker", "effects", "filter", "adjust", "canvas")
        val views = listOf(
            binding.catEdit, binding.catAudio, binding.catText, binding.catSticker,
            binding.catEffects, binding.catFilter, binding.catAdjust, binding.catCanvas
        )
        cats.forEachIndexed { i, cat ->
            val v = views[i]
            val active = cat == activeCat
            val orange  = editorColor(R.color.colorAccentOrange)
            val grey    = editorColor(R.color.colorSubtleText)
            val white   = editorColor(R.color.colorTabLabel)
            // child 0 = icon TextView, child 1 = label TextView, child 2 = indicator View
            (v.getChildAt(0) as? TextView)?.setTextColor(if (active) orange else white)
            (v.getChildAt(1) as? TextView)?.setTextColor(if (active) orange else grey)
            (v.getChildAt(2) as? View)?.setBackgroundColor(if (active) orange else Color.TRANSPARENT)
        }
    }
internal fun populateSubTools(cat: String) {
        binding.subToolsContainer.removeAllViews()
        when (cat) {
            "edit" -> {
                addSubTool("✂", "قص")       { showTrimSheet() }
                addSubTool("✂✂", "تقسيم")     { splitClipAtCurrentPosition() }
                addSubTool("⚡", "السرعة")      { showSpeedSheet() }
                addSubTool("🔄", "تدوير")     { showRotateSheet() }
                addSubTool("📐", "اقتصاص")       { showCropSheet() }
                addSubTool("↩", "عكس")     { reverseVideo() }
                addSubTool("⏸", "تجميد")     { showFreezeFrameSheet() }
                addSubTool("📋", "تكرار")  { duplicateClip() }
                addSubTool("🔊", "الصوت")     { showVolumeSheet(selectedClipIndex) }
                addSubTool("👁", "الشفافية")    { showOpacitySheet() }
                addSubTool("🔍", "تكبير")   { showPanZoomSheet() }
                addSubTool("📌", "استبدال")    { showReplaceClipPicker() }
                addSubTool("🗑", "حذف")     { deleteCurrentClip() }
            }
            "audio" -> {
                addSubTool("🎵", "موسيقى")     { audioPickerLauncher.launch("audio/*") }
                addSubTool("🎙", "تعليق") { showVoiceoverSheet() }
                addSubTool("🎵", "استخراج")   { extractAudio() }
                addSubTool("🔇", "كتم")      { muteVideo() }
                addSubTool("🎛", "مزج")       { showAudioSheet() }
                addSubTool("🔉", "إزالة الضوضاء")   { showNoiseReduceSheet() }
            }
            "text" -> {
                addSubTool("ن", "إضافة نص")   { showTextSheet(null) }
                addSubTool("🎵", "كلمات")    { showLyricsDialog() }
                addSubTool("📝", "ترجمة") { showTextSheet(null) }
                addSubTool("😀", "إيموجي")     { showEmojiSheet() }
                addSubTool("📋", "قائمة النصوص") { toggleTextPanel() }
            }
            "sticker" -> {
                addSubTool("😀", "الكل")       { showEmojiSheet() }
                addSubTool("❤", "قلوب")     { showEmojiSheetCategory("hearts") }
                addSubTool("🎉", "احتفال")     { showEmojiSheetCategory("party") }
                addSubTool("⭐", "نجوم")     { showEmojiSheetCategory("stars") }
                addSubTool("🎵", "موسيقى")     { showEmojiSheetCategory("music") }
            }
            "effects" -> {
                addSubTool("🔀", "انتقال") {
                    if (clips.size < 2) showSnack(getString(R.string.snack_add_two_clips_for_transitions))
                    else showTransitionSheet(selectedClipIndex)
                }
                addSubTool("💚", "كروما")    { showChromaKeySheet() }
                addSubTool("✨", "توهج")       { applyEffectPreset("glow") }
                addSubTool("📺", "ريترو")      { applyEffectPreset("retro") }
                addSubTool("🎬", "سينمائي")  { applyEffectPreset("cinematic") }
                addSubTool("🌊", "ضبابي")       { applyEffectPreset("blur") }
            }
            "filter" -> {
                addSubTool("🎨", "كل الفلاتر") { showFilterSheet() }
                addSubTool("⬛", "B&W")         { applyFilter("hue=s=0") }
                addSubTool("🟤", "كلاسيكي")     { applyFilter("colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131") }
                addSubTool("🔵", "بارد")        { applyFilter("colorbalance=rs=-0.1:gs=-0.1:bs=0.2") }
                addSubTool("🟡", "دافئ")        { applyFilter("colorbalance=rs=0.2:gs=0.1:bs=-0.1") }
                addSubTool("☀️", "مضيء")      { applyFilter("eq=brightness=0.12:contrast=1.1:saturation=1.1") }
                addSubTool("🌈", "نابض")       { applyFilter("eq=saturation=1.6:contrast=1.15") }
                addSubTool("🎞", "باهت")        { applyFilter("eq=brightness=-0.1:contrast=0.85:saturation=0.7") }
                addSubTool("🎭", "دراما")       { applyFilter("eq=contrast=1.4:brightness=-0.05:saturation=0.6,vignette=PI/5") }
                addSubTool("🌅", "غروب")      { applyFilter("colorbalance=rs=0.3:gs=0.1:bs=-0.2,eq=saturation=1.3") }
            }
            "adjust" -> {
                addSubTool("🎚", "الكل")        { showColorAdjustSheet() }
                addSubTool("☀️", "السطوع") { showSingleAdjust("brightness") }
                addSubTool("◑", "التباين")    { showSingleAdjust("contrast") }
                addSubTool("🌈", "التشبع") { showSingleAdjust("saturation") }
                addSubTool("🔆", "الإضاءة") { showSingleAdjust("highlights") }
                addSubTool("🌑", "الظلال")    { showSingleAdjust("shadows") }
                addSubTool("🔪", "الحدة")    { showSingleAdjust("sharpen") }
                addSubTool("🌡", "الحرارة")       { showSingleAdjust("temperature") }
                addSubTool("📷", "تثبيت")  { showStabilizeSheet() }
            }
            "canvas" -> {
                addSubTool("🖼", "خلفية")     { showCanvasSheet() }
                addSubTool("📐", "النسبة")      { showCropSheet() }
                addSubTool("📱", "PIP")        { showPipSheet() }
                addSubTool("➕", "إضافة مقطع")   { addVideoLauncher.launch("video/*") }
            }
        }
    }
internal fun addSubTool(icon: String, label: String, onClick: () -> Unit) {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((80 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            isClickable = true; isFocusable = true
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { onClick() }
        }
        container.addView(TextView(this).apply {
            text = icon
            textSize = if (icon.length > 2) 16f else 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(editorColor(R.color.colorTextMuted))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * dp).toInt()
            }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        binding.subToolsContainer.addView(container)
    }
internal fun setupUndoRedo() {
        binding.btnUndo.setOnClickListener {
            if (undoRedo.canUndo()) {
                undoRedo.performUndo()
                showSnack(getString(R.string.snack_undo_done))
            } else showSnack(getString(R.string.snack_nothing_to_undo))
        }
        binding.btnRedo.setOnClickListener {
            if (undoRedo.canRedo()) {
                undoRedo.performRedo()
                showSnack(getString(R.string.snack_redo_done))
            } else showSnack(getString(R.string.snack_nothing_to_redo))
        }
    }

    // ── Clip Management ───────────────────────────────────────

    /**
     * Batch import preserving order. Duration probing runs with limited parallelism;
     * thumbnails load sequentially to limit memory and main-thread pressure.
     */
internal fun importInitialClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val heavy = uris.size > 6
        if (heavy) binding.loadingScreen.root.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                VideoImportIntentParser.takePersistableReadPermissions(contentResolver, uris)
                val io = Dispatchers.IO.limitedParallelism(3)
                val built = coroutineScope {
                    uris.mapIndexed { idx, uri ->
                        async(io) {
                            idx to VideoClip(uri = uri, durationMs = getClipDurationMs(uri))
                        }
                    }.awaitAll().sortedBy { it.first }.map { it.second }
                }
                clips.clear()
                clips.addAll(built)
                clipAdapter.notifyDataSetChanged()
                if (clips.isNotEmpty()) {
                    selectedClipIndex = 0
                    clipAdapter.setSelected(0)
                    playClip(0)
                }
                updateClipCountBadge()
                rebuildTimeline()
                loadThumbsForTimeline()
                loadThumbsStaggered(clips.indices.toList())
                showSnack(
                    resources.getQuantityString(
                        R.plurals.snack_clips_imported,
                        clips.size,
                        clips.size
                    )
                )
            } finally {
                if (heavy) binding.loadingScreen.root.visibility = View.GONE
            }
        }
    }

    /** Sequential thumbnails with small delay — avoids flooding memory when importing many clips. */
internal fun addClip(uri: Uri) {
        try {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        } catch (_: SecurityException) { /* picker may not grant persistable */ }
        lifecycleScope.launch {
            val dur = withContext(Dispatchers.IO) { getClipDurationMs(uri) }
            clips.add(VideoClip(uri = uri, durationMs = dur))
            val newIdx = clips.size - 1
            clipAdapter.notifyDataSetChanged()
            if (clips.size == 1) { selectedClipIndex = 0; clipAdapter.setSelected(0); playClip(0) }
            loadThumb(newIdx)
            updateClipCountBadge()
            rebuildTimeline()
            loadThumbsForTimeline()
            showSnack(getString(R.string.snack_clip_added_numbered, clips.size))
        }
    }
internal fun updateClipCountBadge() {
        binding.tvClipCount.text = if (clips.size == 1)
            getString(R.string.clip_count_single)
        else
            getString(R.string.clip_count_plural, clips.size)
    }
    internal suspend fun getClipDurationMs(uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            val r = MediaMetadataRetriever()
            when (uri.scheme) {
                "file" -> r.setDataSource(uri.path)
                else -> r.setDataSource(this@VideoEditingActivity, uri)
            }
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release(); d
        } catch (_: Exception) { 0L }
    }
internal fun playClip(idx: Int) {
        if (idx < 0 || idx >= clips.size) return
        selectedClipIndex = idx
        // If player has items, just seek. If not, reload.
        if (player.mediaItemCount > 0 && player.mediaItemCount == clips.size) {
            player.seekToDefaultPosition(idx)
            player.playWhenReady = true
        } else {
            reloadPlaylist(maintainPosition = false, targetIdx = idx)
        }
        binding.tvVideoName.text = clips[idx].uri.lastPathSegment?.substringAfterLast("/") ?: "Video ${idx + 1}"
        if (::multiTrackTimeline.isInitialized) multiTrackTimeline.selectVideoBlock(idx)
    }
internal fun reloadPlaylist(maintainPosition: Boolean = true, targetIdx: Int? = null) {
        val currentGlobalTime = if (player.mediaItemCount > 0) {
            getClipCumulativeStartMs(player.currentMediaItemIndex) + player.currentPosition
        } else 0L

        isVideoLoaded = false
        binding.loadingScreen.root.visibility = View.VISIBLE
        player.stop()

        val mediaItems = clips.map { clip ->
            val builder = MediaItem.Builder().setUri(clip.uri)
            // Constraint 1: Explicitly handle clipping globally
            val dur = if (clip.endTrimMs > 0L) clip.endTrimMs else clip.durationMs
            if (dur > 0L) {
                builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startTrimMs)
                        .setEndPositionMs(dur)
                        .build()
                )
            }
            builder.build()
        }
        player.setMediaItems(mediaItems)
        player.prepare()

        if (maintainPosition && currentGlobalTime > 0) {
            // Constraint 2 and 3: Preserve Global Position & seekTo(window, pos)
            val (idx, locMs) = getPlaylistPositionFromGlobalTime(currentGlobalTime)
            if (idx in clips.indices) player.seekTo(idx, locMs)
        } else if (targetIdx != null && targetIdx in clips.indices) {
            player.seekToDefaultPosition(targetIdx)
        } else if (selectedClipIndex in clips.indices) {
            player.seekToDefaultPosition(selectedClipIndex)
        }
        player.playWhenReady = true // Constraint 4 (start playing smoothly)
    }
internal fun getPlaylistPositionFromGlobalTime(globalPosMs: Long): Pair<Int, Long> {
        var cumulative = 0L
        for (i in clips.indices) {
            val dur = effectiveDurationMs(clips[i])
            if (globalPosMs < cumulative + dur || i == clips.size - 1) {
                return Pair(i, globalPosMs - cumulative)
            }
            cumulative += dur
        }
        return Pair(0, 0L)
    }
    internal suspend fun transcodeForPlayback(clip: VideoClip): File? {
        val input = materializeLocalPath(clip.uri) ?: return null
        val out = getOutputFile("compat")
        val cmd = "-i \"$input\" " +
                  "-vf \"scale='min(iw,1920)':'min(ih,1080)':force_original_aspect_ratio=decrease\" " +
                  "-c:v mpeg4 -q:v 5 -c:a aac -b:a 96k " +
                  "-movflags +faststart \"${out.absolutePath}\" -y"
        val result = withContext(Dispatchers.IO) { FfmpegExecutor.executeSync(cmd) }
        return if (FfmpegExecutor.isSuccess(result) && out.exists()) out else null
    }

    // ── Progress Updater + Text Preview ───────────────────────
internal fun startProgressUpdater() {
        progressUpdateJob?.cancel()
        progressUpdateJob = lifecycleScope.launch {
            while (isActive) {
                val localPos = player.currentPosition
                val currentIdx = player.currentMediaItemIndex
                val globalPos = getClipCumulativeStartMs(currentIdx) + localPos
                val totalDuration = clips.sumOf { effectiveDurationMs(it) }

                if (player.isPlaying || isVideoLoaded) {
                    binding.tvCurrentTime.text = fmtMs(globalPos)
                    binding.tvDuration.text = "${fmtMs(globalPos)} / ${fmtMs(totalDuration)}"
                    if (::multiTrackTimeline.isInitialized) {
                        multiTrackTimeline.currentPositionMs = globalPos
                    }
                }
                // Always update text overlay preview
                updateTextPreview(globalPos / 1000f)
                delay(100)
            }
        }
    }

    // ── Live Text Preview on Video (Interactive DraggableTextView) ────────────

    // Track currently selected DraggableTextView
    internal var selectedDraggable: com.DM.VideoEditor.customviews.DraggableTextView? = null
    internal var overlaysVersion = 0L
internal fun bumpOverlaysVersion() {
        overlaysVersion = System.currentTimeMillis()
        binding.textPreviewContainer.tag = -1L
    }

internal fun updatePlayPause() {
        binding.btnPlayPause.setImageResource(
            if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    // ── Multi-track Timeline setup ─────────────────────────────

    /**
     * Rebuild all timeline blocks from the current clips + text overlays lists.
     * Must be called any time clips or text overlays change.
     */

    /** Returns the cumulative start position (ms) of clip[index] in the project. */
internal fun getClipCumulativeStartMs(index: Int): Long {
        var ms = 0L
        for (i in 0 until index) ms += effectiveDurationMs(clips[i])
        return ms
    }

    /** Effective playback duration of a clip respecting trim and speed. */
internal fun effectiveDurationMs(clip: VideoClip): Long {
        val raw = if (clip.endTrimMs > 0L) clip.endTrimMs - clip.startTrimMs else clip.durationMs
        return (raw / clip.speedFactor).toLong().coerceAtLeast(100L)
    }

    /**
     * Loads a single thumbnail per clip (from mid-clip) and pushes them into
     * MultiTrackTimelineView; replaces the old loadFrames() call.
     * BUG-1 FIX: extracts from mid-clip, recycles original bitmap after scaling,
     * does NOT call rebuildTimeline() — callers do that separately.
     */

    /**
     * Recycles the timeline thumbnail for the given clip index.
     * Call before removing or replacing a clip to avoid bitmap memory leaks.
     */
internal fun execFfmpegClipReplace(
        undoBaseline: VideoClip,
        outputPrefix: String,
        buildCmd: (inPath: String, outAbs: String) -> String
    ) {
        val idx = selectedClipIndex
        lifecycleScope.launch {
            showLoading(true)
            try {
                val input = withContext(Dispatchers.IO) { materializeLocalPath(clips[idx].uri) }
                if (input == null) {
                    showSnack(getString(R.string.snack_cannot_read_file))
                    return@launch
                }
                val out = getOutputFile(outputPrefix)
                val cmd = buildCmd(input, out.absolutePath)
                Log.d("DmEditor", "FFmpeg: $cmd")
                val result = withContext(Dispatchers.IO) { FfmpegExecutor.executeSync(cmd) }
                if (!FfmpegExecutor.isSuccess(result) || !out.exists() || out.length() == 0L) {
                    Log.e("DmEditor", "FFmpeg failed: ${result.exceptionOrNull()}")
                    showSnack(getString(R.string.snack_operation_failed))
                    return@launch
                }
                val newUri = Uri.fromFile(out)
                val dur = withContext(Dispatchers.IO) { getClipDurationMs(newUri) }
                val newClip = undoBaseline.copy(
                    uri = newUri,
                    durationMs = dur,
                    startTrimMs = 0L,
                    endTrimMs = 0L
                )
                undoRedo.register(
                    undo = {
                        clips[idx] = undoBaseline
                        clipAdapter.notifyItemChanged(idx)
                        thumbnailCache.remove(idx)
                        reloadPlaylist(maintainPosition = true, targetIdx = idx)
                        rebuildTimeline(); loadThumb(idx); loadThumbsForTimeline()
                    },
                    redo = {
                        clips[idx] = newClip
                        clipAdapter.notifyItemChanged(idx)
                        thumbnailCache.remove(idx)
                        reloadPlaylist(maintainPosition = true, targetIdx = idx)
                        rebuildTimeline(); loadThumb(idx); loadThumbsForTimeline()
                    }
                )
                clips[idx] = newClip
                clipAdapter.notifyItemChanged(idx)
                thumbnailCache.remove(idx)
                reloadPlaylist(maintainPosition = true, targetIdx = idx)
                rebuildTimeline()
                loadThumbsForTimeline()
                withContext(Dispatchers.IO) { saveToMediaStore(out) }
                showSnack(getString(R.string.snack_audio_saved))
            } finally {
                showLoading(false)
            }
        }
    }
    internal suspend fun materializeLocalPath(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext uri.path
        try {
            val ext = contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.let { if (it == "mpeg" || it == "quicktime") "mp4" else it }
                ?: "mp4"
            val key = uri.toString().replace(Regex("[^a-zA-Z0-9]"), "_").takeLast(60)
            val tmp = File(cacheDir, "ffin_$key.$ext")
            if (!tmp.exists() || tmp.length() == 0L) {
                contentResolver.openInputStream(uri)?.use { inp ->
                    tmp.outputStream().use { out -> inp.copyTo(out) }
                }
            }
            EditorCacheUtils.enforceFfinCacheBudget(cacheDir)
            if (tmp.exists() && tmp.length() > 0L) tmp.absolutePath else null
        } catch (e: Exception) {
            Log.e("materializeLocalPath", "Failed for $uri : $e")
            null
        }
    }



    /** Long-press on clip strip: shortcuts without duplicating the whole bottom toolbar. */

    // ── TRIM ─────────────────────────────────────────────────


    // ── SPLIT CLIP AT CURRENT POSITION ────────────────────────

    /**
     * Copy a URI to a local cache file and return its absolute path.
     * This is required whenever FFmpeg needs to read the SAME source
     * more than once (e.g. two sequential split commands), because a
     * SAF-backed inputs can be one-shot; we copy to a real file instead.
     * is a one-shot pipe: the OS closes it after the first read and any
     * subsequent FFmpeg session gets "SAF id N not found / moov atom not found".
     */

    // ── TEXT OVERLAYS ─────────────────────────────────────────

    @SuppressLint("InflateParams")


    // ── LYRICS / SUBTITLE BATCH MODE ─────────────────────────


    // ── EMOJI ────────────────────────────────────────────────



    // ── TRANSITIONS ───────────────────────────────────────────
    // ── VOLUME ────────────────────────────────────────────────
    // ── AUDIO ─────────────────────────────────────────────────
    // ── CROP ──────────────────────────────────────────────────
    // ── EXPORT DIALOG ─────────────────────────────────────────

    // ── EXPORT (foreground service — survives background / screen off) ─

    // ── General Helpers ───────────────────────────────────────
internal fun showLoading(show: Boolean) { binding.loadingScreen.root.visibility = if (show) View.VISIBLE else View.GONE }
internal fun showSnack(msg: String) { Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show() }
internal fun getOutputFile(prefix: String, ext: String = "mp4"): File {
        val dir = getExternalFilesDir(null) ?: cacheDir; dir.mkdirs()
        return File(dir, "${prefix}_${System.currentTimeMillis()}.$ext")
    }
internal fun fmtMs(ms: Long): String { val s = ms / 1000; return String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60) }

    // ── EMOJI CATEGORY SHORTCUT ───────────────────────────────

}






