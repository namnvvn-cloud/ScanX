package com.scanx.app.data

/** Một thư mục để gom nhóm tài liệu (mục "New Folder" trong menu "..."). */
data class FolderMeta(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
)
