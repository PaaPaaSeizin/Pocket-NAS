package jp.pocketnas.manager

data class FileItem(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long
)