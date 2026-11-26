package jp.pocketnas.manager

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private val sortKeys = arrayOf("NAME_ASC", "NAME_DESC", "DATE_DESC", "DATE_ASC", "SIZE_DESC", "SIZE_ASC")
    private val sortLabels = arrayOf("名前 ↑", "名前 ↓", "新しい順", "古い順", "サイズ大", "サイズ小")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<MaterialSwitch>(R.id.swDark).apply {
            isChecked = prefs.useDarkTheme
            setOnCheckedChangeListener { _, checked ->
                prefs.useDarkTheme = checked
                AppCompatDelegate.setDefaultNightMode(
                    if (checked) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }

        findViewById<MaterialSwitch>(R.id.swTrash).apply {
            isChecked = prefs.useTrash
            setOnCheckedChangeListener { _, checked -> prefs.useTrash = checked }
        }

        findViewById<MaterialSwitch>(R.id.swPreload).apply {
            isChecked = prefs.preload
            setOnCheckedChangeListener { _, checked -> prefs.preload = checked }
        }

        findViewById<Spinner>(R.id.spSort).apply {
            adapter = ArrayAdapter(this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item, sortLabels)
            setSelection(sortKeys.indexOf(prefs.defaultSort).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    prefs.defaultSort = sortKeys[pos]
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }

        findViewById<TextView>(R.id.tvHost).text =
            "接続先: ${prefs.host ?: "未接続"}"

        findViewById<MaterialButton>(R.id.btnForget).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("ペアリング解除")
                .setMessage("サーバーとの接続情報を削除します。次回PIN認証が必要です。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("解除") { _, _ ->
                    prefs.clearAuth()
                    Toast.makeText(this, "解除しました", Toast.LENGTH_SHORT).show()
                    finish()
                }.show()
        }
    }
}