package com.stan.libbylight.player

enum class PlayerReadiness {
    Preparing,
    Buffering,
    Ready,
    Unavailable,
    Error,
}

data class PlayerState(
    val title: String = "Audiobook",
    val chapter: String? = null,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val currentPartIndex: Int = 0,
    val isPlaying: Boolean = false,
    val playbackSpeed: Double = 1.0,
    val controlsFound: Boolean = false,
    val pageUrl: String = "",
    val diagnostic: String = "Waiting for player…",
    val readiness: PlayerReadiness = PlayerReadiness.Preparing,
)
