package com.stan.libbylight

import android.app.Application
import com.stan.libbylight.library.AudiobookProgressStore
import com.stan.libbylight.library.LocalBookRepository
import com.stan.libbylight.player.LocalPlaybackController

class BardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AudiobookProgressStore.init(this)
        LocalBookRepository.init(this)
        LocalPlaybackController.init(this)
    }
}
