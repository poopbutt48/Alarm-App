package com.cartel.qralarm

import android.app.Activity
import android.content.Intent
import android.hardware.*
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Shown while the alarm rings. The sound only stops once the chosen task is done.
 * With extra "disarm" = true it is used to switch the alarm off (same task required).
 */
class RingActivity : Activity(), SensorEventListener {
    private val disarm by lazy { intent.getBooleanExtra("disarm", false) }
    private lateinit var task: Task
    private var need = 1

    private lateinit var info: TextView
    private lateinit var action: Button
    private lateinit var answerBox: EditText

    private var progress = 0
    private var mathAnswer = 0
    private var lastShake = 0L
    private var sensors: SensorManager? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progress = b?.getInt("progress") ?: 0
        task = Alarm.task(this)
        need = Alarm.amount(this, task)

        info = TextView(this).apply { textSize = 24f; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40) }
        action = Button(this).apply { textSize = 24f }
        answerBox = EditText(this).apply {
            textSize = 28f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(TextView(this@RingActivity).apply {
                text = if (disarm) "Do your task to turn the alarm off" else "WAKE UP"
                textSize = 32f
                gravity = Gravity.CENTER
            })
            addView(info)
            addView(answerBox)
            addView(action)
        })
        setupTask()
    }

    private fun setupTask() {
        answerBox.visibility = if (task == Task.MATH) android.view.View.VISIBLE else android.view.View.GONE
        action.isEnabled = true
        when (task) {
            Task.QR -> {
                info.text = "Go scan your saved code."
                action.text = "SCAN"
                action.setOnClickListener { doScan() }
                doScan()
            }
            Task.PHOTO -> {
                val label = Alarm.prefs(this).getString("label", null)
                info.text = "Take a photo of: ${label ?: "?"}"
                action.text = "OPEN CAMERA"
                action.setOnClickListener { takePhoto() }
            }
            Task.MATH -> {
                action.text = "SUBMIT"
                action.setOnClickListener { checkMath() }
                nextProblem()
            }
            Task.STEPS, Task.SHAKE -> {
                action.text = if (task == Task.STEPS) "Start walking…" else "Shake it!"
                action.isEnabled = false
                sensors = getSystemService(SensorManager::class.java)
                showCount()
            }
        }
    }

    // ----- QR -----
    private fun doScan() = scan {
        if (it == Alarm.prefs(this).getString("qr", null)) done()
        else Toast.makeText(this, "Wrong code. Go find the right one.", Toast.LENGTH_SHORT).show()
    }

    // ----- photo -----
    @Deprecated("Plain Activity result API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_PHOTO || res != RESULT_OK) return
        val want = Alarm.prefs(this).getString("label", null) ?: return
        info.text = "Checking photo…"
        labelLastPhoto { labels ->
            if (labels.any { it.text.equals(want, ignoreCase = true) }) done()
            else info.text = "No \"$want\" found. Saw: " +
                labels.take(5).joinToString { it.text }.ifEmpty { "nothing recognisable" } +
                "\nTry again, closer / better lit."
        }
    }

    // ----- math -----
    private fun nextProblem() {
        val a = Random.nextInt(12, 50)
        val m = Random.nextInt(3, 10)
        val c = Random.nextInt(10, 100)
        mathAnswer = a * m + c
        info.text = "Problem ${progress + 1} of $need\n\n$a × $m + $c = ?"
        answerBox.setText("")
    }

    private fun checkMath() {
        if (answerBox.text.toString().trim().toIntOrNull() == mathAnswer) {
            progress++
            if (progress >= need) done() else nextProblem()
        } else {
            Toast.makeText(this, "Wrong — new problem", Toast.LENGTH_SHORT).show()
            nextProblem()
        }
    }

    // ----- steps + shake -----
    private fun showCount() {
        val unit = if (task == Task.STEPS) "steps" else "shakes"
        info.text = "$progress / $need $unit"
    }

    override fun onResume() {
        super.onResume()
        val sm = sensors ?: return
        val type = if (task == Task.STEPS) Sensor.TYPE_STEP_DETECTOR else Sensor.TYPE_ACCELEROMETER
        val allowed = task != Task.STEPS ||
            checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val s = if (allowed) sm.getDefaultSensor(type) else null
        if (s == null) {
            Toast.makeText(this, "Can't use the sensor for this task (missing or no permission) — solve math instead", Toast.LENGTH_LONG).show()
            sensors = null
            task = Task.MATH
            need = 3
            progress = 0
            setupTask()
            return
        }
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        sensors?.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> progress++
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = e.values
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = SystemClock.elapsedRealtime()
                if (g < 2.2f || now - lastShake < 250) return
                lastShake = now
                progress++
            }
            else -> return
        }
        showCount()
        if (progress >= need) done()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ----- finish -----
    private fun done() {
        if (isFinishing) return
        if (disarm) Alarm.cancel(this)
        else startService(Intent(this, AlarmService::class.java).setAction("stop"))
        Alarm.prefs(this).edit().putBoolean("ringing", false).apply()
        Toast.makeText(this, if (disarm) "Alarm turned off" else "Good morning!", Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        o.putInt("progress", progress)
    }

    @Deprecated("Block back while ringing")
    override fun onBackPressed() {
        if (disarm) super.onBackPressed()
    }
}
