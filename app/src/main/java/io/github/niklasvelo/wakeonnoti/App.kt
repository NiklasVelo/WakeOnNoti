package io.github.niklasvelo.wakeonnoti

import android.app.Application
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.system.AutoAiCleanup
import io.github.niklasvelo.wakeonnoti.system.KeepAliveService
import io.github.niklasvelo.wakeonnoti.system.ListenerDoctor
import io.github.niklasvelo.wakeonnoti.system.Notifications
import io.github.niklasvelo.wakeonnoti.system.Watchdog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)  // first: Store reports there what it took out of an old config
        Store.init(this)
        Notifications.createChannels(this)
        AutoAiCleanup.run(this)
        ListenerDoctor.start(this)
        Watchdog.schedule(this)
        KeepAliveService.sync(this)
    }
}
