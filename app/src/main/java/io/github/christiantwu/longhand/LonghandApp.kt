package io.github.christiantwu.longhand

import android.app.Application
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.work.Work
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val TAG = "Longhand"

class LonghandApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Work.createChannels(this)
        appScope.launch {
            if (Settings(this@LonghandApp).current().setupDone) Work.schedulePeriodicScan(this@LonghandApp)
        }
    }
}
