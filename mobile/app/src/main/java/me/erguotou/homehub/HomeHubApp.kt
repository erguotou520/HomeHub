package me.erguotou.homehub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import me.erguotou.homehub.util.TempFiles

class HomeHubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Scratch copies handed to other apps ("用其他应用打开") never outlive
        // a session by much — reclaim whatever a kill or crash left behind.
        TempFiles.sweep(this)
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
