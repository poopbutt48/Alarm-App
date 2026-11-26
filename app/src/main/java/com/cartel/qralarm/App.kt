package com.cartel.qralarm

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import android.widget.*
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.Calendar

object Alarm {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("a", Context.MODE_PRIVATE)

    // setAlarmClock needs no exact-alarm permission and survives Doze.
    fun schedule(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return
        val t = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p.getInt("h", 7))
            set(Calendar.MINUTE, p.getInt("m", 0))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) add(Calendar.DAY_OF_YEAR, 1)
        }
        val fire = PendingIntent.getBroadcast(
            c, 0, Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val show = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        c.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(t.timeInMillis, show), fire)
    }
}

fun Activity.scan(onOk: (String) -> Unit) {
    GmsBarcodeScanning.getClient(this).startScan()
        .addOnSuccessListener { r -> r.rawValue?.let(onOk) }
        .addOnFailureListener { Toast.makeText(this, "Scan failed: ${it.message}", Toast.LENGTH_LONG).show() }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Alarm.prefs(c).getBoolean("on", false)) {
            c.startForegroundService(Intent(c, AlarmService::class.java))
            Alarm.schedule(c) // next day
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Alarm.schedule(c)
}

class AlarmService : Service() {
    private var player: MediaPlayer? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (player != null) return START_NOT_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ring", "Alarm", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) }
        )
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "ring")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm")
            .setContentText("Scan your QR code to stop")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        runCatching {
            val am = getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@AlarmService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            isLooping = true
            prepare()
            start()
        }
        getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        player?.release()
        player = null
        getSystemService(Vibrator::class.java).cancel()
        super.onDestroy()
    }
}

class RingActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(Button(this).apply {
            text = "SCAN QR TO STOP"
            textSize = 28f
            setOnClickListener { go() }
        })
        go()
    }

    private fun go() = scan {
        val saved = Alarm.prefs(this).getString("qr", null)
        if (saved == null || it == saved) {
            startService(Intent(this, AlarmService::class.java).setAction("stop"))
            finishAndRemoveTask()
        } else {
            Toast.makeText(this, "Wrong code. Go to the kitchen.", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Block back while ringing")
    override fun onBackPressed() {}
}

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        val p = Alarm.prefs(this)
        val tp = TimePicker(this).apply { hour = p.getInt("h", 7); minute = p.getInt("m", 0) }
        status = TextView(this).apply { textSize = 18f }

        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 80, 40, 40)
                addView(status)
                addView(tp)
                addView(btn("1. Scan & save your kitchen QR") {
                    scan { p.edit().putString("qr", it).apply(); refresh() }
                })
                addView(btn("2. Set alarm") {
                    val nm = getSystemService(NotificationManager::class.java)
                    when {
                        p.getString("qr", null) == null ->
                            Toast.makeText(this@MainActivity, "Scan your QR first", Toast.LENGTH_SHORT).show()
                        !nm.canUseFullScreenIntent() -> {
                            Toast.makeText(this@MainActivity, "Allow full-screen alerts, then tap again", Toast.LENGTH_LONG).show()
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                        }
                        else -> {
                            p.edit().putInt("h", tp.hour).putInt("m", tp.minute).putBoolean("on", true).apply()
                            Alarm.schedule(this@MainActivity)
                            refresh()
                        }
                    }
                })
                addView(btn("Turn off alarm (needs QR scan)") {
                    scan { if (it == p.getString("qr", null)) { p.edit().putBoolean("on", false).apply(); refresh() } }
                })
                addView(btn("Test alarm now") {
                    startForegroundService(Intent(this@MainActivity, AlarmService::class.java))
                    startActivity(Intent(this@MainActivity, RingActivity::class.java))
                })
            })
        })
        refresh()
    }

    private fun refresh() {
        val p = Alarm.prefs(this)
        val a = if (p.getBoolean("on", false)) "Alarm ON daily at %02d:%02d".format(p.getInt("h", 7), p.getInt("m", 0)) else "Alarm OFF"
        status.text = a + "\nQR saved: " + if (p.getString("qr", null) != null) "yes" else "no"
    }
}
