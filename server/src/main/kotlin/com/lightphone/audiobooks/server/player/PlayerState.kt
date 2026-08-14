package com.lightphone.audiobooks.server.player

enum class PlayerReadiness {
    Preparing,
    Ready,
    Unavailable,
    Error,
}

data class PlayerState(
    val bookId: String = "",
    val title: String = "Audiobook",
    val chapter: String? = null,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val currentPartIndex: Int = 0,
    val partCount: Int = 0,
    val partTitle: String? = null,
    val isPlaying: Boolean = false,
    val playbackSpeed: Double = 1.0,
    val readiness: PlayerReadiness = PlayerReadiness.Preparing,
)
