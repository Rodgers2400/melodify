package com.melodify.melodify

import android.app.Application
import com.melodify.melodify.data.db.AppDatabase
import com.melodify.melodify.data.repository.MusicRepository
import com.melodify.melodify.lyrics.LyricsRepository
import com.melodify.melodify.player.PlayerController

class MelodifyApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val musicRepository: MusicRepository by lazy { MusicRepository(this) }

    val lyricsRepository: LyricsRepository by lazy { LyricsRepository(database) }

    val playerController: PlayerController by lazy {
        PlayerController(this, lyricsRepository)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MelodifyApp
            private set
    }
}
