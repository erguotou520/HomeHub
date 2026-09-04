package me.erguotou.nvr

import android.app.Application
import android.util.Log
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsListener

class NvrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize TBS X5 kernel
        QbSdk.initX5Environment(this, object : QbSdk.PreInitCallback {
            override fun onCoreInitFinished() {
                Log.d("NvrApp", "X5 core init finished")
            }
            override fun onViewInitFinished(success: Boolean) {
                Log.d("NvrApp", "X5 view init finished: $success")
            }
        })
        QbSdk.setTbsListener(object : TbsListener {
            override fun onDownloadFinish(i: Int) {
                Log.d("NvrApp", "X5 download finished: $i")
            }
            override fun onInstallFinish(i: Int) {
                Log.d("NvrApp", "X5 install finished: $i")
            }
            override fun onDownloadProgress(i: Int) {
                Log.d("NvrApp", "X5 download progress: $i")
            }
        })
    }
}
