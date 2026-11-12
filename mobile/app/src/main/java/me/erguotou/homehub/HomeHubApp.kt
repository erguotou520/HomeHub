package me.erguotou.homehub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import me.erguotou.homehub.data.PlaybackPositions
import me.erguotou.homehub.util.TempFiles
import kotlin.concurrent.thread

class HomeHubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Scratch copies handed to other apps ("用其他应用打开") never outlive
        // a session by much — reclaim whatever a kill or crash left behind.
        TempFiles.sweep(this)
        // Resume points nobody came back to within a month are dead weight.
        // Done off the main thread: this is the first read of that prefs file
        // and there is no reason to make the launcher wait for it.
        thread(name = "playback-sweep") {
            runCatching { PlaybackPositions.sweep(this@HomeHubApp) }
        }
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
