package jp.pocketnas.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
// import kotlinx.serialization.Serializable ← 不要なら削除

class TrashManager(private val context: Context) {
    companion object {
        const val TRASH_DIR_NAME = "__trash__"
        const val TRASH_META_DIR = "__trash_meta__"
    }

    fun trashRoot(storageRoot: DocumentFile): DocumentFile? =
        storageRoot.findFile(TRASH_DIR_NAME) ?: storageRoot.createDirectory(TRASH_DIR_NAME)

    fun moveToTrash(storageRoot: DocumentFile, doc: DocumentFile): String? {
        val trashDir = trashRoot(storageRoot) ?: return null
        val originalName = doc.name ?: return null
        val uniqueName = "${System.currentTimeMillis()}__$originalName"
        val dst = trashDir.createDirectory(uniqueName) ?: return null
        if (!copyRecursive(doc, dst)) {
            try { dst.delete() } catch (_: Exception) {}
            return null
        }
        doc.delete()
        // メタを保存
        context.getSharedPreferences("pocketnas_trash", Context.MODE_PRIVATE)
            .edit()
            .putString("$uniqueName\$orig", originalName)
            .putLong("$uniqueName\$time", System.currentTimeMillis())
            .apply()
        return uniqueName
    }

    fun list(storageRoot: DocumentFile): List<TrashItem> {
        val trashDir = storageRoot.findFile(TRASH_DIR_NAME) ?: return emptyList()
        val sp = context.getSharedPreferences("pocketnas_trash", Context.MODE_PRIVATE)
        return trashDir.listFiles().mapNotNull { f ->
            val n = f.name ?: return@mapNotNull null
            TrashItem(
                name = n,
                original = sp.getString("$n\$orig", n.substringAfter("__", n)) ?: n,
                trashedAt = sp.getLong("$n\$time", f.lastModified()),
                size = if (f.isDirectory) -1 else f.length(),
                directory = f.isDirectory
            )
        }.sortedByDescending { it.trashedAt }
    }

    fun restore(storageRoot: DocumentFile, trashName: String, targetDir: DocumentFile): Boolean {
        val trashDir = storageRoot.findFile(TRASH_DIR_NAME) ?: return false
        val src = trashDir.findFile(trashName) ?: return false
        val sp = context.getSharedPreferences("pocketnas_trash", Context.MODE_PRIVATE)
        val original = sp.getString("$trashName\$orig", trashName.substringAfter("__")) ?: return false
        if (targetDir.findFile(original) != null) return false
        val ok = copyRecursive(src, targetDir, forceName = original)
        if (ok) {
            src.delete()
            sp.edit().remove("$trashName\$orig").remove("$trashName\$time").apply()
        }
        return ok
    }

    fun deletePermanently(storageRoot: DocumentFile, trashName: String): Boolean {
        val trashDir = storageRoot.findFile(TRASH_DIR_NAME) ?: return false
        val f = trashDir.findFile(trashName) ?: return false
        val ok = f.delete()
        if (ok) {
            context.getSharedPreferences("pocketnas_trash", Context.MODE_PRIVATE)
                .edit().remove("$trashName\$orig").remove("$trashName\$time").apply()
        }
        return ok
    }

    fun emptyAll(storageRoot: DocumentFile): Boolean {
        val trashDir = storageRoot.findFile(TRASH_DIR_NAME) ?: return true
        var ok = true
        for (f in trashDir.listFiles()) {
            if (!f.delete()) ok = false
        }
        context.getSharedPreferences("pocketnas_trash", Context.MODE_PRIVATE).edit().clear().apply()
        return ok
    }

    private fun copyRecursive(src: DocumentFile, dstDir: DocumentFile, forceName: String? = null): Boolean {
        return try {
            if (src.isDirectory) {
                val name = forceName ?: src.name ?: return false
                val dst = dstDir.createDirectory(name) ?: return false
                for (c in src.listFiles()) {
                    val n = c.name ?: continue
                    if (!copyRecursive(c, dst, null)) return false
                }
                true
            } else {
                val name = forceName ?: src.name ?: return false
                val mime = src.type ?: "application/octet-stream"
                val dst = dstDir.createFile(mime, name) ?: return false
                context.contentResolver.openInputStream(src.uri)?.use { i ->
                    context.contentResolver.openOutputStream(dst.uri, "w")?.use { o ->
                        i.copyTo(o, 64 * 1024)
                    } ?: return false
                } ?: return false
                true
            }
        } catch (_: Exception) { false }
    }
}