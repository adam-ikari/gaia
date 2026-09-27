package dev.hwkbd.ime.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev.hwkbd.ime.ImeRuntime
import dev.hwkbd.ime.R
import dev.hwkbd.ime.keymap.KeyLayoutStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImeRuntime.init(this)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, PrefsFragment())
                .commit()
        }
    }

    class PrefsFragment : PreferenceFragmentCompat() {

        private val importLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) importDict(uri) else toast(getString(R.string.import_no_file))
            }

        private val exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
                if (uri != null) exportUserDict(uri)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)

            // Titan 2 首次打开设置:弹窗说明 Fn 键需设为 Ctrl(五键选字与 Ctrl 组合依赖它)
            if (android.os.Build.MODEL.contains("Titan", ignoreCase = true) &&
                !ImeRuntime.prefs.getBoolean("titan2_fn_hint_shown", false)
            ) {
                ImeRuntime.prefs.edit().putBoolean("titan2_fn_hint_shown", true).apply()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.titan2_fn_hint_title)
                    .setMessage(R.string.titan2_fn_hint_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }

            findPreference<Preference>("layer_map")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LayerMapActivity::class.java)); true
            }
            findPreference<Preference>("layer_reset")?.setOnPreferenceClickListener {
                KeyLayoutStore.reset(requireContext())
                toast(getString(R.string.done_reset)); true
            }
            findPreference<Preference>("dict_import")?.setOnPreferenceClickListener {
                importLauncher.launch(arrayOf("text/plain", "application/gzip", "application/octet-stream"))
                true
            }
            findPreference<Preference>("dict_export")?.setOnPreferenceClickListener {
                exportLauncher.launch("hwkbd_user_dict.txt"); true
            }
            findPreference<Preference>("dict_reset")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_dict_reset)
                    .setMessage(android.R.string.ok)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        ImeRuntime.userDict.clearAll()
                        ImeRuntime.saveUserDict(requireContext())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<Preference>("dict_stats")?.setOnPreferenceClickListener { showStats(); true }
            findPreference<Preference>("playground")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), dev.hwkbd.ime.playground.PlaygroundActivity::class.java)); true
            }
            findPreference<Preference>("probe")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), dev.hwkbd.ime.probe.KeyProbeActivity::class.java)); true
            }
            findPreference<Preference>("about")?.setOnPreferenceClickListener { showAbout(); true }
        }

        private fun importDict(uri: Uri) {
            val ctx = requireContext()
            // 导入前自动备份原用户词库
            val file = ctx.getFileStreamPath(ImeRuntime.USER_DICT_FILE)
            if (file != null && file.exists()) {
                runCatching { file.copyTo(ctx.getFileStreamPath(ImeRuntime.USER_DICT_FILE + ".bak"), overwrite = true) }
            }
            val lines = mutableListOf<String>()
            var ok = 0
            var bad = 0
            runCatching {
                val stream = ctx.contentResolver.openInputStream(uri) ?: return
                val buf = java.io.PushbackInputStream(stream, 2)
                val magic = ByteArray(2)
                val n = buf.read(magic)
                if (n == 2) buf.unread(magic)
                val unzip = n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
                val br = BufferedReader(
                    InputStreamReader(if (unzip) GZIPInputStream(buf) else buf, Charsets.UTF_8),
                )
                br.useLines { seq -> seq.forEach { line -> if (line.isNotBlank()) lines.add(line) } }
            }.onFailure {
                toast(it.message ?: "read error")
                return
            }
            for (line in lines) {
                if (ImeRuntime.userDict.importLine(line)) ok++ else bad++
            }
            ImeRuntime.saveUserDict(ctx)
            toast(getString(R.string.import_result, ok, bad))
        }

        private fun exportUserDict(uri: Uri) {
            runCatching {
                requireContext().contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(ImeRuntime.userDict.exportLines().joinToString("\n").toByteArray())
                }
            }
        }

        private fun showStats() {
            val d = ImeRuntime.dict
            val msg = buildString {
                appendLine(
                    if (d != null) getString(R.string.dict_stats_builtin, d.size)
                    else getString(R.string.dict_stats_loading)
                )
                appendLine(getString(R.string.dict_stats_user_entries, ImeRuntime.userDict.entryCount))
                append(getString(R.string.dict_stats_user_freq, ImeRuntime.userDict.trackedWordCount))
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_dict_stats)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

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

        private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
    }
}
