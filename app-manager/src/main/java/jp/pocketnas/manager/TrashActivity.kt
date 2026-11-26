package jp.pocketnas.manager

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.URLEncoder

class TrashActivity : AppCompatActivity() {

    private lateinit var api: PocketNasApi
    private lateinit var adapter: TrashAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)
        api = PocketNasApi(Prefs(this), this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        emptyView = findViewById(R.id.tvEmpty)

        adapter = TrashAdapter { item -> showActions(item) }
        findViewById<RecyclerView>(R.id.rvTrash).apply {
            layoutManager = LinearLayoutManager(this@TrashActivity)
            adapter = this@TrashActivity.adapter
            addItemDecoration(DividerItemDecoration(this@TrashActivity, DividerItemDecoration.VERTICAL))
        }
        findViewById<MaterialButton>(R.id.btnEmptyAll).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("ゴミ箱を空にする")
                .setMessage("元に戻せません。よろしいですか？")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("実行") { _, _ ->
                    api.request("DELETE", "/api/trash?name=ALL") { code, _ ->
                        toast(if (code == 200) "空にしました" else "失敗: $code")
                        load()
                    }
                }.show()
        }
        load()
    }

    private fun load() {
        api.listTrash { list ->
            adapter.submitList(list)
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showActions(item: TrashItem) {
        val options = arrayOf("元に戻す", "完全削除", "キャンセル")
        MaterialAlertDialogBuilder(this)
            .setTitle(item.original)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        api.request("POST",
                            "/api/trash/restore?name=${enc(item.name)}&to="
                        ) { code, _ ->
                            toast(if (code == 200) "戻しました" else "失敗: $code")
                            load()
                        }
                    }
                    1 -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("完全削除")
                            .setMessage("「${item.original}」を完全に削除しますか？")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("削除") { _, _ ->
                                api.request("DELETE", "/api/trash?name=${enc(item.name)}") { code, _ ->
                                    toast(if (code == 200) "削除しました" else "失敗: $code")
                                    load()
                                }
                            }.show()
                    }
                }
            }.show()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}