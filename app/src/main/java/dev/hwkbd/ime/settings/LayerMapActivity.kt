package dev.hwkbd.ime.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.hwkbd.ime.R
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyLayoutStore

/**
 * 三层键位映射编辑:纯文本(「键=输出」/ key:键名),
 * 保存校验全量行,出错时指出第一个错误行号。
 */
class LayerMapActivity : AppCompatActivity() {

    private lateinit var et: EditText

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                        et.setText(r.readText())
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layer_map)
        et = findViewById(R.id.et_layers)
        et.setText(KeyLayoutStore.loadTextOrDefault(this))

        findViewById<android.widget.Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<android.widget.Button>(R.id.btn_reset).setOnClickListener {
            et.setText(KeyLayout.default().formatText())
        }
        findViewById<android.widget.Button>(R.id.btn_export).setOnClickListener { share() }
        findViewById<android.widget.Button>(R.id.btn_import).setOnClickListener {
            importLauncher.launch(arrayOf("text/plain"))
        }
    }

    private fun save() {
        val text = et.text.toString()
        val parsed = KeyLayout.parseText(text)
        if (parsed.layout == null) {
            val line = parsed.errors.first()
            AlertDialog.Builder(this)
                .setTitle(R.string.layer_map_title)
                .setMessage(getString(R.string.layer_invalid_line, line))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        KeyLayoutStore.save(this, text)
        Toast.makeText(this, R.string.done_saved, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, et.text.toString())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.layer_export)))
    }
}
