package jp.pocketnas.manager

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(
    private val prefs: Prefs,
    private val thumbs: ThumbnailLoader,
    private val onItemClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit,
    private val onSelectionChanged: () -> Unit
) : ListAdapter<FileItem, FileAdapter.VH>(Diff) {

    private var selectionMode = false
    private val selected = mutableSetOf<String>()
    private val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    fun isSelectionMode() = selectionMode
    fun selectedItems(): List<FileItem> = currentList.filter { it.path in selected }
    fun enterSelection(item: FileItem) {
        selectionMode = true; selected.clear(); selected.add(item.path)
        notifyDataSetChanged(); onSelectionChanged()
    }
    fun toggleSelection(item: FileItem) {
        if (!selectionMode) return
        if (!selected.add(item.path)) selected.remove(item.path)
        if (selected.isEmpty()) exitSelection() else {
            // ★Step7: 全体を再バインドするとサムネイルが点滅して崩れて見えるため、
            // 変わった行だけを更新する
            val pos = currentList.indexOfFirst { it.path == item.path }
            if (pos >= 0) notifyItemChanged(pos) else notifyDataSetChanged()
            onSelectionChanged()
        }
    }
    fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false; selected.clear()
        notifyDataSetChanged(); onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.tvIcon)
        private val thumb: ImageView = itemView.findViewById(R.id.ivThumb)
        private val favIcon: ImageView = itemView.findViewById(R.id.ivFav)
        private val name: TextView = itemView.findViewById(R.id.tvName)
        private val meta: TextView = itemView.findViewById(R.id.tvMeta)
        private val check: CheckBox = itemView.findViewById(R.id.cbSelect)

        fun bind(item: FileItem) {
            val lower = item.name.lowercase(Locale.ROOT)
            val isImage = !item.directory && lower.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic)$"))
            // ★Step2: 動画もサムネイル表示する（取れない場合は再生アイコンにフォールバック）
            val isVideo = !item.directory && lower.matches(Regex(".*\\.(mp4|mkv|webm|mov|m4v|3gp|avi)$"))
            val isPdf = !item.directory && lower.endsWith(".pdf")

            if (isImage || isVideo || isPdf) {
                icon.visibility = View.GONE
                thumb.visibility = View.VISIBLE
                thumbs.load(item.path, thumb, isVideo = isVideo, isPdf = isPdf)
            } else {
                thumb.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.text = iconFor(item)
            }

            name.text = item.name
            meta.text = buildString {
                if (item.directory) append("フォルダ") else append(formatSize(item.size))
                // ★修正: modified はミリ秒で返るので *1000 しない
                if (item.modified > 0) { append("  ·  "); append(dateFmt.format(Date(item.modified))) }
            }
            favIcon.visibility = if (prefs.isFavorite(item.path)) View.VISIBLE else View.GONE
            val isSel = item.path in selected
            // ★Step7: チェックボックスはアイコンに重ねて表示するので、
            // 表示/非表示で行のレイアウトは変わらない
            check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            check.isChecked = isSel
            // ★Step7: 未選択時は XML のリップル背景（selectableItemBackground）に戻す。
            // 以前は transparent をセットしていたため、一度バインドされた行は
            // タッチフィードバックが消えてしまっていた
            if (isSel) {
                itemView.setBackgroundResource(R.color.selection_highlight)
            } else {
                val tv = TypedValue()
                itemView.context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, tv, true
                )
                itemView.setBackgroundResource(tv.resourceId)
            }
            itemView.setOnClickListener {
                if (selectionMode) toggleSelection(item) else onItemClick(item)
            }
            itemView.setOnLongClickListener {
                if (selectionMode) toggleSelection(item) else onLongClick(item); true
            }
        }

        private fun iconFor(item: FileItem): String {
            if (item.directory) return "📁"
            val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                "mp4","mkv","mov","avi","webm","m4v" -> "🎬"
                "mp3","wav","flac","ogg","m4a","aac" -> "🎵"
                "pdf" -> "📕"
                "zip","rar","7z","tar","gz" -> "🗜️"
                "apk" -> "📦"
                "txt","md","log" -> "📄"
                "json","xml","yaml","yml","toml" -> "⚙️"
                "kt","java","py","js","ts","go","rs","c","cpp","h","sh","html","css" -> "💻"
                else -> "📎"
            }
        }

        private fun formatSize(size: Long): String = when {
            size >= 1L shl 30 -> "%.2f GB".format(size / (1024.0 * 1024 * 1024))
            size >= 1L shl 20 -> "%.1f MB".format(size / (1024.0 * 1024))
            size >= 1L shl 10 -> "%.1f KB".format(size / 1024.0)
            else -> "$size B"
        }
    }

    object Diff : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(a: FileItem, b: FileItem) = a.path == b.path
        override fun areContentsTheSame(a: FileItem, b: FileItem) = a == b
    }
}