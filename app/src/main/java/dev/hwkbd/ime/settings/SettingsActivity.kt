package dev.hwkbd.ime.settings

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import dev.hwkbd.ime.R
import dev.hwkbd.ime.ImeRuntime
import dev.hwkbd.ime.service.ImeService
import dev.hwkbd.ime.telemetry.UserData

/**
 * 设置页。
 *
 * ## 换引擎后少了三组入口(2026-10-03)
 *
 * 旧的 [dev.hwkbd.ime.engine.UserDict] 是应用自己的 JSON 用户词频,所以设置页有
 * 「导出 / 导入 / 重置 / 统计词条数」四组入口。换 librime 后:
 *
 * | 原入口 | 现在的处置 | 为什么 |
 * | --- | --- | --- |
 * | 导出用户词 | **删掉** | 词频在 LevelDB 里,格式是 librime 私有实现;解析它要引入一个不可靠的依赖 |
 * | 导入用户词 | **删掉** | 同上 |
 * | 重置用户词 | 改成「清空学习记录」 | LevelDB 可以整个目录删掉,librime 下次访问会重建 |
 * | 词典统计 | 改成只显示**目录大小与文件数** | 拿不到词条数(同上的格式问题) |
 *
 * 这不是功能退化到没有,而是**换了一种实现**:用户词频仍然存在、仍然自动学习,
 * 只是不再由应用自己记账,也就没有导出/逐词删除这类需要读内部格式的操作。
 *
 * 代价要说清:**逐词删除用户词**这个能力没了。候选栏的长按删除入口因此失效
 * (见 [dev.hwkbd.ime.strategy.InputMethod.supportsWordRemoval])。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PrefsFragment())
                .commit()
        }
    }

    class PrefsFragment : PreferenceFragmentCompat() {

        /** 打开语音开关时等权限结果的这一小段时间里,开关应落在什么状态。 */
        private var pendingVoiceEnable = false

        private val recordAudioPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!pendingVoiceEnable) return@registerForActivityResult
            pendingVoiceEnable = false
            val pref = findPreference<SwitchPreferenceCompat>(ImeService.PREF_VOICE_INPUT)
            if (granted) {
                ImeRuntime.prefs.edit().putBoolean(ImeService.PREF_VOICE_INPUT, true).apply()
                pref?.isChecked = true
            } else {
                pref?.isChecked = false
                Toast.makeText(requireContext(), R.string.voice_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)

            // Titan 2 首次打开设置:弹窗说明 Fn 键需设为 Ctrl(五键选字与 Ctrl 组合依赖它)
            if (Build.MODEL.contains("Titan", ignoreCase = true) &&
                !ImeRuntime.prefs.getBoolean("titan2_fn_hint_shown", false)
            ) {
                ImeRuntime.prefs.edit().putBoolean("titan2_fn_hint_shown", true).apply()
            }
            bindSectionHeaders()
            bindVoiceSwitch()
        }

        /**
         * 语音开关:识别跑在本机,唯一的门槛是麦克风权限。
         *
         * IME 是 Service,不能自己弹权限框 —— 所以权限只能在这里要。权限没给时
         * 让开关保持关闭,而不是留一个「开着但长按没反应」的假状态。
         */
        private fun bindVoiceSwitch() {
            val pref = findPreference<SwitchPreferenceCompat>(ImeService.PREF_VOICE_INPUT) ?: return
            // 有的用户在系统设置里撤销权限:开门就对账,把开关落回真实的可用状态。
            if (pref.isChecked && !hasRecordAudioPermission()) {
                pref.isChecked = false
                ImeRuntime.prefs.edit().putBoolean(ImeService.PREF_VOICE_INPUT, false).apply()
            }
            pref.setOnPreferenceChangeListener { _, newValue ->
                if (newValue != true) return@setOnPreferenceChangeListener true
                if (hasRecordAudioPermission()) return@setOnPreferenceChangeListener true
                pendingVoiceEnable = true
                recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                false
            }
        }

        private fun hasRecordAudioPermission(): Boolean =
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        private fun bindSectionHeaders() {
            findPreference<Preference>("playground")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), dev.hwkbd.ime.playground.PlaygroundActivity::class.java))
                true
            }
            findPreference<Preference>("probe")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), dev.hwkbd.ime.probe.KeyProbeActivity::class.java))
                true
            }
            findPreference<Preference>("about")?.setOnPreferenceClickListener { showAbout(); true }
            findPreference<Preference>("licenses")?.setOnPreferenceClickListener { showLicenses(); true }
            findPreference<Preference>("dict_stats")?.setOnPreferenceClickListener {
                showStats(); true
            }
            findPreference<Preference>("dict_reset")?.setOnPreferenceClickListener {
                confirmClear(); true
            }
        }

        private fun confirmClear() {
            val ctx = requireContext()
            if (!UserData.exists(ctx)) {
                android.widget.Toast.makeText(ctx, R.string.dict_nothing_to_clear, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.pref_dict_reset)
                .setMessage(getString(R.string.dict_clear_confirm, UserData.location(ctx)))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val ok = UserData.clear(ctx)
                    android.widget.Toast.makeText(
                        ctx,
                        if (ok) R.string.dict_cleared else R.string.dict_clear_failed,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /**
         * 词典统计。
         *
         * **只报目录大小与文件数**,拿不到词条数 —— LevelDB 的 `.ldb` 格式是
         * librime 私有实现,读它要引依赖(见 [UserData] 类注释)。
         * 少报几个数比报错数好。
         */
        private fun showStats() {
            val ctx = requireContext()
            val (bytes, files) = UserData.stats(ctx)
            val msg = buildString {
                appendLine(getString(R.string.dict_stats_engine, librimeVersion()))
                appendLine(
                    getString(
                        R.string.dict_stats_user_size,
                        if (bytes > 0) formatBytes(bytes) else "—",
                        files,
                    )
                )
                append(UserData.location(ctx))
            }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.pref_dict_stats)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun librimeVersion(): String =
            ImeRuntime.rime?.version ?: "—"

        private fun showAbout() {
            val ver = runCatching {
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            }.getOrNull() ?: "?"
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_about)
                .setMessage(getString(R.string.about_body, ver))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 第三方开源许可说明。文本很长但短于 AlertDialog 高度不保证,
        // setMessage 的正文自带 ScrollView,超长会自动可滚。
        //
        // **librime 是 BSD、librime 的 deps 各有各自的许可**(LevelDB/BSD、OpenCC/Apache、
        // yaml-cpp/MIT、marisa-trie/BSL-1.0、yaml-cpp 等)。换引擎后这里的内容需要更新 ——
        // 见 THIRD_PARTY_NOTICES.md,那里是完整清单。
        private fun showLicenses() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_licenses)
                .setMessage(R.string.licenses_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun formatBytes(n: Long): String = when {
            n >= 1_048_576 -> "%.1f MB".format(n / 1_048_576.0)
            n >= 1024 -> "%.0f KB".format(n / 1024.0)
            else -> "$n B"
        }
    }
}
