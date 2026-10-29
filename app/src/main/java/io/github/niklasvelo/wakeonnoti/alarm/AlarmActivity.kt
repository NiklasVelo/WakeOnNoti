package io.github.niklasvelo.wakeonnoti.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.niklasvelo.wakeonnoti.system.Notifications
import io.github.niklasvelo.wakeonnoti.ui.AppTheme
import java.text.DateFormat
import java.util.Date

/** Full-screen, over the lock screen: what fired, and one big Stop button. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // opened from the fallback notification: we are in the foreground now, so the
        // real alarm (looping sound, vibration) is allowed to start
        if (AlarmService.current.value == null) {
            val pending = AlarmInfo.from(intent.extras)
            if (pending == null) {
                finish()
                return
            }
            Notifications.cancelFallback(this)
            AlarmService.start(this, pending)
        }

        setContent {
            AppTheme(dark = true) {
                val info by AlarmService.current.collectAsState()
                val seen = remember { mutableStateOf(false) }
                LaunchedEffect(info) {
                    if (info != null) seen.value = true
                    else if (seen.value) finish()  // stopped (here, from the notification, or by auto-stop)
                }
                AlarmScreen(info) { AlarmService.stop(this@AlarmActivity) }
            }
        }
    }
}

@Composable
private fun AlarmScreen(info: AlarmInfo?, onStop: () -> Unit) {
    // the Surface provides the content color; a bare background left the text black on black
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AlarmContent(info, onStop) }
}

@Composable
private fun AlarmContent(info: AlarmInfo?, onStop: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Filled.Notifications, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(info?.at ?: System.currentTimeMillis())),
                fontSize = 56.sp, fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(24.dp))
            Text(info?.rule ?: "Alarm", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            if (info != null) {
                Text(info.summary, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info.text.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(88.dp),
            shape = RoundedCornerShape(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("Stop", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
