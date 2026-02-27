package com.gameswu.nyadeskpet.live2d

import kotlinx.serialization.Serializable

@Serializable
data class ParameterSet(
    val id: String,
    val value: Float,
    val weight: Float = 1.0f,
    val transitionInMs: Long = 500,
    val holdMs: Long = 1000,
    val transitionOutMs: Long = 500
)
