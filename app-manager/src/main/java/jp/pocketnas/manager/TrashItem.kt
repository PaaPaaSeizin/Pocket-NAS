package jp.pocketnas.manager

data class TrashItem(
    val name: String,
    val original: String,
    val trashedAt: Long,
    val size: Long,
    val directory: Boolean
)