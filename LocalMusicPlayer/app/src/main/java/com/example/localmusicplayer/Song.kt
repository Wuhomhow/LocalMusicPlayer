package com.example.localmusicplayer

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val rawResId: Int = 0,
    val uriString: String? = null // 手机本地存储音频的 URI
)
