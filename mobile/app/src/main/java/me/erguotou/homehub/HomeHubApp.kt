package me.erguotou.homehub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class HomeHubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val upload = NotificationChannel(
            "homehub_upload",
            "上传",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "后台上传进度" }

        manager.createNotificationChannel(upload)
    }
}
