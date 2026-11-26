package jp.pocketnas.manager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrashAdapter(
    private val onClick: (TrashItem) -> Unit
) : ListAdapter<TrashItem, TrashAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trash, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)

        private val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

        fun bind(item: TrashItem) {
            tvName.text = (if (item.directory) "📁 " else "📄 ") + item.original
            tvMeta.text = "削除: ${dateFmt.format(Date(item.trashedAt))}" +
                    if (item.size > 0 && !item.directory)
                        "  ·  ${"%1.1f".format(Locale.US, item.size / 1024.0)} KB"
                    else ""
            itemView.setOnClickListener { onClick(item) }
        }
    }

    object Diff : DiffUtil.ItemCallback<TrashItem>() {
        override fun areItemsTheSame(a: TrashItem, b: TrashItem) = a.name == b.name
        override fun areContentsTheSame(a: TrashItem, b: TrashItem) = a == b
    }
}