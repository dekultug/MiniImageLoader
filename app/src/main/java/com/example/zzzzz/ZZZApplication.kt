package com.example.zzzzz

import com.dekultug.tempbase.BaseApplication
import com.example.zzzzz.mock.FakeImageServer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ZZZApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        FakeImageServer.start(this)
    }
}
