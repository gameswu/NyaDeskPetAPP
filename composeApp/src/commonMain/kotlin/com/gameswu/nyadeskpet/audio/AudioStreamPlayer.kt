package com.gameswu.nyadeskpet.audio

import com.gameswu.nyadeskpet.PlatformContext

/**
 * Platform-specific audio streaming player.
 */
expect class AudioStreamPlayer(context: PlatformContext) {
    /**
     * Starts a new audio stream with the given MIME type.
     */
    fun startStream(mimeType: String)

    /**
     * Appends a chunk of base64-encoded audio data to the stream.
     */
    fun appendChunk(base64Data: String)

    /**
     * Ends the audio stream and releases resources.
     */
    fun endStream()

    /**
     * Sets the player volume.
     */
    fun setVolume(volume: Float)

    /**
     * Registers a callback to receive lip-sync values (audio amplitude).
     */
    fun setLipSyncCallback(callback: (Float) -> Unit)

    /**
     * Stops and releases the current audio playback immediately.
     */
    fun stop()
}
