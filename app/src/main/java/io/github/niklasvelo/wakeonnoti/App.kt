package io.github.niklasvelo.wakeonnoti

import android.app.Application
import io.github.niklasvelo.wakeonnoti.actions.AutoAiInbox
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.system.KeepAliveService
import io.github.niklasvelo.wakeonnoti.system.Notifications
import io.github.niklasvelo.wakeonnoti.system.Watchdog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        EventLog.init(this)
        Notifications.createChannels(this)
        Watchdog.schedule(this)
        KeepAliveService.sync(this)
        // the process lives as long as Android keeps the listener bound, so does this
        AutoAiInbox.init(this)
    }
}
