package com.cartel.qralarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Shown while the alarm rings. The sound only stops once the chosen task is done.
 * With extra "disarm" = true it is used to switch the alarm off (same task required).
 */
class RingActivity : ComponentActivity(), SensorEventListener {
    private val disarm by lazy { intent.getBooleanExtra("disarm", false) }
    private lateinit var task: Task
    private var need = 1

    private lateinit var info: TextView
    private lateinit var action: Button
    private lateinit var answerBox: EditText
    private lateinit var preview: PreviewView
    private lateinit var flip: Button
    private lateinit var giveUp: Button

    private var progress = 0
    private var mathAnswer = 0
    private var lastShake = 0L
    private var sensors: SensorManager? = null

    // camera
    private var camProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var frontCamera = false
    private val camExecutor = Executors.newSingleThreadExecutor()
    private var orientation: OrientationEventListener? = null
    private var aiFails = 0
    private var counter: RepCounter? = null
    private val poseDetector by lazy {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
        )
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this) { if (disarm) finish() } // no escaping while ringing
        progress = b?.getInt("progress") ?: 0
        task = Alarm.task(this)
        need = Alarm.amount(this, task)

        info = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER; setPadding(0, 24, 0, 24) }
        action = Button(this).apply { textSize = 24f }
        answerBox = EditText(this).apply {
            textSize = 28f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        flip = Button(this).apply {
            text = "Flip camera"
            setOnClickListener { frontCamera = !frontCamera; startCamera() }
        }
        giveUp = Button(this).apply {
            text = "Can't do it? Solve 10 math problems instead"
            setOnClickListener { switchToMath(10) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 32)
            addView(TextView(this@RingActivity).apply {
                text = if (disarm) "Do your task to turn the alarm off" else "WAKE UP"
                textSize = 30f
                gravity = Gravity.CENTER
            })
            addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(info)
            addView(answerBox)
            addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@RingActivity).apply { addView(flip); addView(giveUp) })
        })
        setupTask()
    }

    private fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

    private fun setupTask() {
        show(answerBox, task == Task.MATH)
        show(preview, task.camera)
        show(flip, task.camera)
        show(giveUp, false)
        action.isEnabled = true
        show(action, true)

        if (task.camera && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "No camera permission — solve math instead", Toast.LENGTH_LONG).show()
            switchToMath(10)
            return
        }

        when (task) {
            Task.AI_SHOW -> {
                val goal = Alarm.prefs(this).getString("goal", null).orEmpty()
                info.text = "Show the camera: $goal\nthen tap CHECK"
                action.text = "CHECK"
                action.setOnClickListener { aiCheck(goal) }
                frontCamera = false
                startCamera()
                lifecycleScope.launch {
                    val s = runCatching { Nano.status() }.getOrDefault(FeatureStatus.UNAVAILABLE)
                    if (s != FeatureStatus.AVAILABLE && task == Task.AI_SHOW) {
                        Toast.makeText(this@RingActivity, "On-device AI is ${Nano.statusName(s)} — solve math instead", Toast.LENGTH_LONG).show()
                        switchToMath(10)
                    }
                }
            }
            Task.PUSHUPS, Task.SQUATS -> {
                counter = RepCounter(pushups = task == Task.PUSHUPS)
                show(action, false)
                frontCamera = true
                info.text = "Prop the phone up so it sees your whole body from the side.\n0 / $need"
                startCamera()
                // Escape hatch in case tracking just won't work (bad light etc.).
                preview.postDelayed({ if (!isFinishing && task.camera) show(giveUp, true) }, 3 * 60_000L)
            }
            Task.QR -> {
                info.text = "Go scan your saved code."
                action.text = "SCAN"
                action.setOnClickListener { doScan() }
                doScan()
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
                registerSensor()
            }
        }
    }

    private fun switchToMath(n: Int) {
        stopCamera()
        sensors?.unregisterListener(this)
        sensors = null
        task = Task.MATH
        need = n
        progress = 0
        setupTask()
    }

    // ----- camera (AI check + rep counting) -----
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            if (isFinishing || !task.camera) return@addListener
            val provider = f.get().also { camProvider = it }
            provider.unbindAll()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                if (counter != null) {
                    val a = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    a.setAnalyzer(camExecutor) { analyzePose(it) }
                    analysis = a
                    provider.bindToLifecycle(this, selector, pv, a)
                } else {
                    provider.bindToLifecycle(this, selector, pv)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
                show(giveUp, true)
            }
        }, mainExecutor)
    }

    private fun stopCamera() {
        camProvider?.unbindAll()
        analysis = null
        counter = null
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyzePose(proxy: ImageProxy) {
        val img = proxy.image ?: run { proxy.close(); return }
        poseDetector.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { pose ->
                val c = counter ?: return@addOnSuccessListener
                val newRep = c.onPose(pose)
                info.text = "${c.hint}\n${c.reps} / $need ${if (task == Task.PUSHUPS) "push-ups" else "squats"}"
                if (newRep && c.reps >= need) done()
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun aiCheck(goal: String) {
        val frame = preview.bitmap ?: run {
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show(); return
        }
        action.isEnabled = false
        info.text = "AI is looking…"
        lifecycleScope.launch {
            runCatching { Nano.check(frame, goal) }
                .onSuccess { v ->
                    if (v.pass) { done(); return@launch }
                    aiFails++
                    info.text = "Not yet — AI saw: ${v.saw.ifEmpty { "?" }}\nShow: $goal"
                }
                .onFailure { e ->
                    aiFails++
                    info.text = "AI error: ${e.message}\nTap CHECK to try again."
                }
            action.isEnabled = true
            if (aiFails >= 5) show(giveUp, true)
        }
    }

    // Keep camera frames upright relative to how the phone is actually held,
    // so "lying flat" vs "standing" checks work even when the phone is on its side.
    override fun onStart() {
        super.onStart()
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                analysis?.targetRotation = when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }.also { it.enable() }
    }

    override fun onStop() {
        orientation?.disable()
        super.onStop()
    }

    // ----- QR -----
    private fun doScan() = scan {
        if (it == Alarm.prefs(this).getString("qr", null)) done()
        else Toast.makeText(this, "Wrong code. Go find the right one.", Toast.LENGTH_SHORT).show()
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
        registerSensor()
    }

    private fun registerSensor() {
        val sm = sensors ?: return
        val type = if (task == Task.STEPS) Sensor.TYPE_STEP_DETECTOR else Sensor.TYPE_ACCELEROMETER
        val allowed = task != Task.STEPS ||
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val s = if (allowed) sm.getDefaultSensor(type) else null
        if (s == null) {
            Toast.makeText(this, "Can't use the sensor for this task — solve math instead", Toast.LENGTH_LONG).show()
            switchToMath(3)
            return
        }
        sm.unregisterListener(this)
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
        stopCamera()
        if (disarm) Alarm.cancel(this)
        else startService(Intent(this, AlarmService::class.java).setAction("stop"))
        Alarm.prefs(this).edit().putBoolean("ringing", false).apply()
        Toast.makeText(this, if (disarm) "Alarm turned off" else "Good morning!", Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        camExecutor.shutdown()
        poseDetector.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        o.putInt("progress", progress)
    }
}
