package com.example.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VaultItem(
    val id: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val imagePath: String? = null
)
