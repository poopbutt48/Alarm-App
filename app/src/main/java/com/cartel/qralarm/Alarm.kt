package com.cartel.qralarm

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
import java.util.Calendar

/** The tasks you can pick to turn the alarm off. */
enum class Task(val title: String) {
    QR("Scan a QR / barcode"),
    PHOTO("Take a photo of something"),
    STEPS("Walk a number of steps"),
    MATH("Solve math problems"),
    SHAKE("Shake the phone"),
}

object Alarm {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("a", Context.MODE_PRIVATE)

    fun task(c: Context): Task =
        runCatching { Task.valueOf(prefs(c).getString("task", null)!!) }.getOrDefault(Task.QR)

    /** How many steps / problems / shakes the chosen task needs. */
    fun amount(c: Context, t: Task): Int = prefs(c).getInt("amount_$t", when (t) {
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

// ---------- shared helpers for QR + photo tasks ----------

fun Activity.scan(onOk: (String) -> Unit) {
    GmsBarcodeScanning.getClient(this).startScan()
        .addOnSuccessListener { r -> r.rawValue?.let(onOk) }
        .addOnFailureListener { Toast.makeText(this, "Scan failed: ${it.message}", Toast.LENGTH_LONG).show() }
}

const val REQ_PHOTO = 42

private fun Context.photoUri(): Uri {
    val f = File(cacheDir, "photos/shot.jpg").apply { parentFile?.mkdirs(); delete() }
    return FileProvider.getUriForFile(this, "$packageName.files", f)
}

/** Opens the system camera app. Result arrives in onActivityResult(REQ_PHOTO). */
fun Activity.takePhoto() {
    val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri())
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivityForResult(i, REQ_PHOTO)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No camera app found", Toast.LENGTH_LONG).show()
    }
}

/** Runs on-device ML Kit image labeling on the photo just taken. */
fun Context.labelLastPhoto(onDone: (List<ImageLabel>) -> Unit) {
    val f = File(cacheDir, "photos/shot.jpg")
    if (!f.exists() || f.length() == 0L) { onDone(emptyList()); return }
    val img = runCatching { InputImage.fromFilePath(this, Uri.fromFile(f)) }.getOrNull()
        ?: run { onDone(emptyList()); return }
    ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.4f).build())
        .process(img)
        .addOnSuccessListener { onDone(it.sortedByDescending { l -> l.confidence }) }
        .addOnFailureListener {
            Toast.makeText(this, "Couldn't analyse photo: ${it.message}", Toast.LENGTH_LONG).show()
            onDone(emptyList())
        }
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
