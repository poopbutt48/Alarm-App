package com.cartel.qralarm

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.widget.Toast
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.Calendar

/** The tasks you can pick to turn the alarm off. */
enum class Task(val title: String, val camera: Boolean = false) {
    AI_SHOW("Show the AI something (e.g. a milk jug)", camera = true),
    PUSHUPS("Push-ups (AI counts them)", camera = true),
    SQUATS("Squats (AI counts them)", camera = true),
    QR("Scan a QR / barcode"),
    STEPS("Walk a number of steps"),
    MATH("Solve math problems"),
    SHAKE("Shake the phone"),
}

object Alarm {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("a", Context.MODE_PRIVATE)

    fun task(c: Context): Task =
        runCatching { Task.valueOf(prefs(c).getString("task", null)!!) }.getOrDefault(Task.MATH)

    /** How many steps / problems / shakes the chosen task needs. */
    fun amount(c: Context, t: Task): Int = prefs(c).getInt("amount_$t", when (t) {
        Task.PUSHUPS -> 10
        Task.SQUATS -> 15
        Task.STEPS -> 50
        Task.MATH -> 3
        Task.SHAKE -> 30
        else -> 1
    })

    private fun firePi(c: Context) = PendingIntent.getBroadcast(
        c, 0, Intent(c, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

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
        val show = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        c.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(t.timeInMillis, show), firePi(c))
    }

    fun cancel(c: Context) {
        prefs(c).edit().putBoolean("on", false).apply()
        c.getSystemService(AlarmManager::class.java).cancel(firePi(c))
    }
}

// ---------- QR helper ----------

fun Activity.scan(onOk: (String) -> Unit) {
    GmsBarcodeScanning.getClient(this).startScan()
        .addOnSuccessListener { r -> r.rawValue?.let(onOk) }
        .addOnFailureListener { Toast.makeText(this, "Scan failed: ${it.message}", Toast.LENGTH_LONG).show() }
}

// ---------- alarm plumbing ----------

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
        Alarm.prefs(this).edit().putBoolean("ringing", true).apply()

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
            .setContentText("Task: ${Alarm.task(this).title} — tap to do it")
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
        Alarm.prefs(this).edit().putBoolean("ringing", false).apply()
        super.onDestroy()
    }
}
