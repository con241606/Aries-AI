package com.ai.phoneagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ai.phoneagent.databinding.ActivityAboutBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupToolbar()
        setupClickListeners()
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = getColor(R.color.blue_glass_primary)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // 与主页一致：把系统栏 top inset 交给 AppBarLayout 的 padding，避免内容层遮挡导致点击无效。
            binding.appBar.setPadding(0, sys.top, 0, 0)
            insets
        }
    }

    private fun setupToolbar() {
        // 标题改为由页面内容区域展示，避免在沉浸式状态栏下出现重复/裁切。
        binding.topAppBar.title = ""
        binding.topAppBar.setNavigationOnClickListener {
            vibrateLight()
            finish()
        }

        // 返回按钮上移一点点，和主页顶栏图标对齐（主页是 -7dp）。
        val upOffsetPx = -7f * resources.displayMetrics.density
        binding.topAppBar.post {
            for (i in 0 until binding.topAppBar.childCount) {
                val child = binding.topAppBar.getChildAt(i)
                if (child is ImageButton) {
                    child.translationY = upOffsetPx
                }
            }
        }
    }

    private fun setupClickListeners() {
        // 检查更新（占位）
        binding.btnCheckUpdate.setOnClickListener {
            vibrateLight()
            Toast.makeText(this, "检查更新功能稍后接入", Toast.LENGTH_SHORT).show()
        }

        // 更新日志
        binding.root.findViewById<LinearLayout>(R.id.itemChangelog).setOnClickListener {
            vibrateLight()
            showChangelogDialog()
        }

        // 开源许可声明
        binding.root.findViewById<LinearLayout>(R.id.itemLicenses).setOnClickListener {
            vibrateLight()
            showLicensesDialog()
        }

        // 联系方式 - 点击复制邮箱
        binding.root.findViewById<LinearLayout>(R.id.itemContact).setOnClickListener {
            vibrateLight()
            copyToClipboard("jack666_2007@foxmail.com")
            Toast.makeText(this, "邮箱已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        // 开发者
        binding.root.findViewById<LinearLayout>(R.id.itemDeveloper).setOnClickListener {
            vibrateLight()
            Toast.makeText(this, "感谢使用 Phone Agent！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showChangelogDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_changelog, null, false)
        view.findViewById<TextView>(R.id.tvVersion).text = "v1.0.0"
        view.findViewById<TextView>(R.id.tvDate).text = "2026-01-03"
        view.findViewById<TextView>(R.id.tvBody).text = """
            🎉 首个稳定版本发布！

            本次更新内容：
            · 支持 AutoGLM API 接入，实现智能对话
            · 集成 sherpa-ncnn 本地语音识别引擎
            · 支持无障碍服务实现手机自动化操作
            · 悬浮小窗模式，边聊天边操作
            · 优雅的蓝色玻璃拟态 UI 设计
            · 历史对话管理与持久化

            感谢您的使用与支持！
        """.trimIndent()

        MaterialAlertDialogBuilder(this, R.style.BlueGlassAlertDialog)
            .setView(view)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showLicensesDialog() {
        val licenses = listOf(
            License("AndroidX Core KTX", "Kotlin extensions for Android core libraries", "Apache-2.0"),
            License("AndroidX AppCompat", "Backward-compatible Android UI components", "Apache-2.0"),
            License("Material Components", "Material Design components for Android", "Apache-2.0"),
            License("Kotlin Coroutines", "Kotlin coroutines support", "Apache-2.0"),
            License("OkHttp", "HTTP client for Android and Java", "Apache-2.0"),
            License("Gson", "JSON serialization/deserialization library", "Apache-2.0"),
            License("sherpa-ncnn", "Offline speech recognition engine", "Apache-2.0"),
            License("AndroidX RecyclerView", "Efficient list display widget", "Apache-2.0"),
            License("AndroidX ConstraintLayout", "Flexible layout manager", "Apache-2.0"),
        )

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_licenses, null, false)
        val container = view.findViewById<LinearLayout>(R.id.licenseContainer)

        licenses.forEach { lic ->
            val row = layoutInflater.inflate(R.layout.item_license_row, container, false)
            row.findViewById<TextView>(R.id.tvLibName).text = lic.name
            row.findViewById<TextView>(R.id.tvLibDesc).text = lic.description
            row.findViewById<TextView>(R.id.tvLibLicense).text = "许可: ${lic.license}"
            container.addView(row)
        }

        MaterialAlertDialogBuilder(this, R.style.BlueGlassAlertDialog)
            .setView(view)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("email", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun vibrateLight() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30)
                }
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    private data class License(val name: String, val description: String, val license: String)
}
