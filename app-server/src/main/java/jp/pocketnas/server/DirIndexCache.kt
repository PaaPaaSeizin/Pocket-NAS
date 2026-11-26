package jp.pocketnas.server

import java.util.concurrent.ConcurrentHashMap

class DirIndexCache(private val ttlMs: Long = 60_000L) {
    data class EntryItem(
        val name: String, val path: String, val directory: Boolean,
        val size: Long, val modified: Long
    )
    data class ItemList(val items: List<EntryItem>, val timestamp: Long)

    private val cache = ConcurrentHashMap<String, ItemList>()

    fun get(path: String): List<EntryItem>? {
        val e = cache[path] ?: return null
        if (System.currentTimeMillis() - e.timestamp > ttlMs) {
            cache.remove(path); return null
        }
        return e.items
    }

    fun put(path: String, items: List<EntryItem>) {
        cache[path] = ItemList(items, System.currentTimeMillis())
    }

    fun invalidate(path: String) { cache.remove(path) }

    fun invalidatePrefix(prefix: String) {
        cache.keys.filter { it == prefix || it.startsWith("$prefix/") }
            .forEach { cache.remove(it) }
    }
}