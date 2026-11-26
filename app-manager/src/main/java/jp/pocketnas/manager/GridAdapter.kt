package jp.pocketnas.manager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class GridAdapter(
    private val thumbs: ThumbnailLoader,
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit
) : ListAdapter<FileItem, GridAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_grid, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.tvIcon)
        private val name: TextView = itemView.findViewById(R.id.tvName)
        private val thumb: ImageView = itemView.findViewById(R.id.ivThumb)

        fun bind(item: FileItem) {
            name.text = item.name

            // ★Step2: 動画もサムネイル表示する
            val isVideo = !item.directory && isVideoFile(item.name)
            val isPdf = !item.directory && item.name.lowercase(Locale.ROOT).endsWith(".pdf")
            if (!item.directory && (isImageFile(item.name) || isVideo || isPdf)) {
                icon.visibility = View.GONE
                thumb.visibility = View.VISIBLE
                thumbs.load(item.path, thumb, isVideo = isVideo, isPdf = isPdf)
            } else {
                thumb.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.text = iconFor(item)
            }

            itemView.setOnClickListener {
                onClick(item)
            }

            itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        private fun isVideoFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
            return ext in setOf(
                "mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi"
            )
        }

        private fun isImageFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "")
                .lowercase(Locale.ROOT)

            return ext in setOf(
                "jpg", "jpeg", "png", "gif",
                "webp", "bmp", "heic"
            )
        }

        private fun iconFor(item: FileItem): String {
            if (item.directory) return "📁"

            val ext = item.name.substringAfterLast('.', "")
                .lowercase(Locale.ROOT)

            return when (ext) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "🖼️"
                "mp4", "mkv", "mov", "avi", "webm", "m4v" -> "🎬"
                "mp3", "wav", "flac", "ogg", "m4a", "aac" -> "🎵"
                "pdf" -> "📕"
                "zip", "rar", "7z", "tar", "gz" -> "🗜️"
                "apk" -> "📦"
                "txt", "md", "log" -> "📄"
                "json", "xml", "yaml", "yml", "toml" -> "⚙️"
                "kt", "java", "py", "js", "ts", "go", "rs",
                "c", "cpp", "h", "sh", "html", "css" -> "💻"
                else -> "📎"
            }
        }
    }

    object Diff : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(a: FileItem, b: FileItem) =
            a.path == b.path

        override fun areContentsTheSame(a: FileItem, b: FileItem) =
            a == b
    }
}