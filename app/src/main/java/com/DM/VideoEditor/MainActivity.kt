package com.DM.VideoEditor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.util.TypedValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.DM.VideoEditor.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>

    private val selectVideosLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) navigateToEditingScreen(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupMainEdgeToEdgeInsets()

        requestPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
                if (perms.values.all { it }) {
                    selectVideosLauncher.launch("video/*")
                } else {
                    Toast.makeText(this, getString(R.string.permissions_message), Toast.LENGTH_LONG).show()
                }
            }

        // مشروع جديد
        binding.addVideoButton.setOnClickListener {
            if (arePermissionsGranted()) selectVideosLauncher.launch("video/*")
            else showPermissionDialog()
        }

        // الشريط السفلي — Home
        binding.navHome.setOnClickListener { /* already on home */ }

        // الشريط السفلي — المسودات
        binding.navDrafts.setOnClickListener { showDraftsDialog() }

        binding.linkPrivacy.setOnClickListener { showPrivacyPolicyDialog() }
    }

    /** Dark UI: light system bar icons off; insets on top/bottom chrome only. */
    private fun setupMainEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        EdgeToEdgeInsets.applyTopBarInsets(binding.topBar)
        EdgeToEdgeInsets.applyBottomBarInsets(binding.bottomNav)
        EdgeToEdgeInsets.applyHorizontalInsets(binding.mainScrollView)
    }

    override fun onResume() {
        super.onResume()
        refreshDraftsUI()
    }

    // ── تحديث واجهة المسودات ──────────────────────────────────

    private fun refreshDraftsUI() {
        val drafts = ProjectDraftManager.listDrafts(this)

        // شارة العدد على زر المسودات
        binding.tvDraftsBadge.apply {
            visibility = if (drafts.isEmpty()) View.GONE else View.VISIBLE
            text = drafts.size.toString()
        }

        // قسم المشاريع الأخيرة
        if (drafts.isEmpty()) {
            binding.tvRecentProjects.visibility = View.GONE
            binding.rvRecentProjects.visibility = View.GONE
            binding.emptyRecentState.visibility = View.VISIBLE
        } else {
            binding.tvRecentProjects.visibility = View.VISIBLE
            binding.rvRecentProjects.visibility = View.VISIBLE
            binding.emptyRecentState.visibility = View.GONE

            binding.rvRecentProjects.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.rvRecentProjects.adapter = DraftMiniAdapter(
                drafts = drafts.take(6),
                onOpen  = { d -> resumeDraft(d) },
                onDelete = { d ->
                    ProjectDraftManager.delete(this, d.id)
                    refreshDraftsUI()
                    Toast.makeText(this, getString(R.string.draft_deleted), Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // ── حوار قائمة المسودات ───────────────────────────────────

    private fun showDraftsDialog() {
        val drafts = ProjectDraftManager.listDrafts(this)
        if (drafts.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_drafts), Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())
        val names = drafts.map { d ->
            "${d.name}  •  ${d.clipCount} مقطع  •  ${fmt.format(Date(d.savedAt))}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.drafts))
            .setItems(names) { _, i -> resumeDraft(drafts[i]) }
            .setNeutralButton("حذف الكل") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("حذف كل المسودات؟")
                    .setMessage("لا يمكن التراجع عن هذا الإجراء.")
                    .setPositiveButton("حذف") { _, _ ->
                        drafts.forEach { ProjectDraftManager.delete(this, it.id) }
                        refreshDraftsUI()
                        Toast.makeText(this, "تم حذف كل المسودات", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun resumeDraft(draft: ProjectDraftManager.DraftSummary) {
        startActivity(Intent(this, VideoEditingActivity::class.java)
            .putExtra("DRAFT_ID", draft.id))
    }

    // ── سياسة الخصوصية ───────────────────────────────────────

    private fun showPrivacyPolicyDialog() {
        val wv = WebView(this)
        val h = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 420f, resources.displayMetrics).toInt()
        wv.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h)
        wv.loadUrl("file:///android_asset/privacy_policy.html")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_policy)
            .setView(wv)
            .setPositiveButton(R.string.open_policy_url) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    // ── الأذونات ─────────────────────────────────────────────

    private fun arePermissionsGranted() = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            checkPerm(Manifest.permission.READ_MEDIA_VIDEO)
        else -> checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun checkPerm(vararg perms: String) =
        perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.permissions_required))
            .setMessage(getString(R.string.permissions_message))
            .setPositiveButton(getString(R.string.grant)) { _, _ -> requestPermissions() }
            .setNegativeButton(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .setCancelable(false).show()
    }

    private fun requestPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        requestPermissionsLauncher.launch(perms)
    }

    private fun navigateToEditingScreen(uris: List<Uri>) {
        startActivity(
            Intent(this, VideoEditingActivity::class.java)
                .putParcelableArrayListExtra("VIDEO_URIS", ArrayList(uris))
        )
    }
}

// ── Adapter للمسودات الأخيرة ──────────────────────────────

class DraftMiniAdapter(
    private val drafts: List<ProjectDraftManager.DraftSummary>,
    private val onOpen: (ProjectDraftManager.DraftSummary) -> Unit,
    private val onDelete: (ProjectDraftManager.DraftSummary) -> Unit
) : RecyclerView.Adapter<DraftMiniAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView    = v.findViewById(R.id.tvDraftName)
        val tvClips: TextView   = v.findViewById(R.id.tvDraftClips)
        val tvDate: TextView    = v.findViewById(R.id.tvDraftDate)
    }

    private val fmt = SimpleDateFormat("dd MMM", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_draft_card, parent, false))

    override fun getItemCount() = drafts.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val d = drafts[pos]
        holder.tvName.text  = d.name
        holder.tvClips.text = "${d.clipCount} مقطع"
        holder.tvDate.text  = fmt.format(Date(d.savedAt))
        holder.itemView.setOnClickListener { onOpen(d) }
        holder.itemView.setOnLongClickListener { onDelete(d); true }
    }
}
