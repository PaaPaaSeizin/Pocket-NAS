package jp.pocketnas.server

import android.content.Context
import androidx.documentfile.provider.DocumentFile

object StorageCache {
    private const val TTL_MS = 30_000L
    private var cached: StorageInfo? = null
    private var cachedAt = 0L

    @Synchronized
    fun get(context: Context, root: DocumentFile?): StorageInfo {
        val now = System.currentTimeMillis()
        if (cached != null && now - cachedAt < TTL_MS) return cached!!
        val fresh = StorageInfo(
            free = StorageUtils.freeSpaceOf(context, root),
            total = StorageUtils.totalSpaceOf(context, root),
            name = StorageUtils.name(context, root)
        )
        cached = fresh
        cachedAt = now
        return fresh
    }

    @Synchronized
    fun invalidate() { cached = null; cachedAt = 0L }
}