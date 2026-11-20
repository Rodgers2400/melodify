package com.melodify.melodify.player

import androidx.media3.exoplayer.ExoPlayer

/**
 * Provides access to the underlying [ExoPlayer] for integration with a media
 * session (the service needs it to build the Media3 session). Kept in a tiny
 * file to avoid leaking the player into the rest of the codebase.
 */
internal fun PlayerController.exoPlayer(): ExoPlayer = this.exo()
