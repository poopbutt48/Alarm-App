package com.cartel.qralarm

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var setupBtn: Button
    private lateinit var amountRow: LinearLayout
    private lateinit var amountBox: EditText
    private lateinit var amountLabel: TextView
    private lateinit var goalBox: EditText
    private lateinit var aiStatus: TextView
    private var selected = Task.MATH
    private var downloading = false

    private val p by lazy { Alarm.prefs(this) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        selected = Alarm.task(this)

        val tp = TimePicker(this).apply { hour = p.getInt("h", 7); minute = p.getInt("m", 0) }
        status = TextView(this).apply { textSize = 18f }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

        val group = RadioGroup(this).apply {
            Task.values().forEachIndexed { i, t ->
                addView(RadioButton(this@MainActivity).apply { id = i + 1; text = t.title; textSize = 17f })
            }
            check(selected.ordinal + 1)
            setOnCheckedChangeListener { _, id -> selected = Task.values()[id - 1]; updateSetupUi() }
        }

        setupBtn = btn("") { runSetup() }
        amountLabel = TextView(this).apply { textSize = 17f }
        amountBox = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setEms(4) }
        amountRow = LinearLayout(this).apply { addView(amountLabel); addView(amountBox) }
        goalBox = EditText(this).apply {
            hint = "What must the camera see? e.g. a milk jug"
            setText(p.getString("goal", ""))
            setSingleLine()
        }
        aiStatus = TextView(this).apply { textSize = 15f }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 80, 40, 40)
                addView(status)
                addView(tp)
                addView(TextView(this@MainActivity).apply { text = "\nTask to turn the alarm off:"; textSize = 18f })
                addView(group)
                addView(goalBox)
                addView(aiStatus)
                addView(setupBtn)
                addView(amountRow)
                addView(btn("Set alarm") { arm(tp.hour, tp.minute) })
                addView(btn("Turn off alarm (requires the task)") {
                    if (!p.getBoolean("on", false)) toast("Alarm is already off")
                    else startActivity(Intent(this@MainActivity, RingActivity::class.java).putExtra("disarm", true))
                })
                addView(btn("Test alarm now") {
                    lifecycleScope.launch {
                        if (!saveSelection()) return@launch
                        startForegroundService(Intent(this@MainActivity, AlarmService::class.java))
                        startActivity(Intent(this@MainActivity, RingActivity::class.java))
                    }
                })
            })
        })
        updateSetupUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Changing the task while the alarm rings would be cheating — send them back to the task.
        if (p.getBoolean("ringing", false)) {
            startActivity(Intent(this, RingActivity::class.java))
        }
        if (::status.isInitialized) refresh()
    }

    private fun updateSetupUi() {
        for (v in listOf(setupBtn, amountRow, goalBox, aiStatus)) v.visibility = View.GONE
        when (selected) {
            Task.QR -> { setupBtn.text = "Scan & save the QR/barcode to use"; setupBtn.visibility = View.VISIBLE }
            Task.AI_SHOW -> {
                goalBox.visibility = View.VISIBLE
                aiStatus.visibility = View.VISIBLE
                refreshAi()
            }
            Task.PUSHUPS, Task.SQUATS, Task.STEPS, Task.MATH, Task.SHAKE -> {
                amountLabel.text = when (selected) {
                    Task.PUSHUPS -> "Push-ups: "
                    Task.SQUATS -> "Squats: "
                    Task.STEPS -> "Steps to walk: "
                    Task.MATH -> "Problems to solve: "
                    else -> "Shakes needed: "
                }
                amountBox.setText(Alarm.amount(this, selected).toString())
                amountRow.visibility = View.VISIBLE
            }
        }
    }

    private fun refreshAi() = lifecycleScope.launch {
        if (downloading) return@launch
        val s = runCatching { Nano.status() }.getOrDefault(FeatureStatus.UNAVAILABLE)
        aiStatus.text = "On-device AI (Gemini Nano): ${Nano.statusName(s)}"
        if (s == FeatureStatus.DOWNLOADABLE) {
            setupBtn.text = "Download the AI model"
            setupBtn.visibility = View.VISIBLE
        } else {
            setupBtn.visibility = View.GONE
        }
    }

    private fun runSetup() {
        if (p.getBoolean("ringing", false)) return
        when (selected) {
            Task.QR -> scan { p.edit().putString("qr", it).apply(); toast("Code saved"); refresh() }
            Task.AI_SHOW -> downloadAi()
            else -> {}
        }
    }

    private fun downloadAi() {
        if (downloading) return
        downloading = true
        setupBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                Nano.download().collect { s ->
                    aiStatus.text = when (s) {
                        is DownloadStatus.DownloadStarted -> "Downloading AI model…"
                        is DownloadStatus.DownloadProgress -> "Downloading AI model… ${s.totalBytesDownloaded / 1_000_000} MB"
                        is DownloadStatus.DownloadCompleted -> "AI model ready"
                        is DownloadStatus.DownloadFailed -> "Download failed: ${s.e.message}"
                        else -> aiStatus.text
                    }
                }
            } catch (e: Exception) {
                aiStatus.text = "Download failed: ${e.message}"
            } finally {
                downloading = false
                setupBtn.isEnabled = true
                refreshAi()
            }
        }
    }

    private fun granted(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Validates and stores the selected task. Returns false if setup is incomplete. */
    private suspend fun saveSelection(): Boolean {
        if (p.getBoolean("ringing", false)) return false
        when (selected) {
            Task.QR -> if (p.getString("qr", null) == null) { toast("Scan your code first"); return false }
            Task.AI_SHOW -> {
                val goal = goalBox.text.toString().trim()
                if (goal.length < 3) { toast("Type what the camera must see, e.g. \"a milk jug\""); return false }
                val s = runCatching { Nano.status() }.getOrDefault(FeatureStatus.UNAVAILABLE)
                if (s != FeatureStatus.AVAILABLE) {
                    toast("The on-device AI is ${Nano.statusName(s)}." +
                        if (s == FeatureStatus.DOWNLOADABLE) " Tap \"Download the AI model\" first." else "")
                    return false
                }
                p.edit().putString("goal", goal).apply()
            }
            Task.PUSHUPS, Task.SQUATS, Task.STEPS, Task.MATH, Task.SHAKE -> {
                val n = amountBox.text.toString().toIntOrNull()
                if (n == null || n < 1 || n > 10000) { toast("Enter a number from 1 to 10000"); return false }
                p.edit().putInt("amount_$selected", n).apply()
            }
        }
        if (selected.camera && !granted(Manifest.permission.CAMERA)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
            toast("Allow camera access, then tap again")
            return false
        }
        if (selected == Task.STEPS && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
            toast("Allow physical activity access (needed to count steps), then tap again")
            return false
        }
        p.edit().putString("task", selected.name).apply()
        refresh()
        return true
    }

    private fun arm(h: Int, m: Int) = lifecycleScope.launch {
        if (!saveSelection()) return@launch
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.canUseFullScreenIntent()) {
            toast("Allow full-screen alerts, then tap again")
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            return@launch
        }
        p.edit().putInt("h", h).putInt("m", m).putBoolean("on", true).apply()
        Alarm.schedule(this@MainActivity)
        refresh()
        toast("Alarm set")
    }

    private fun refresh() {
        val on = p.getBoolean("on", false)
        val t = Alarm.task(this)
        val detail = when (t) {
            Task.QR -> if (p.getString("qr", null) != null) "code saved" else "no code saved"
            Task.AI_SHOW -> "show: " + (p.getString("goal", null) ?: "not set")
            else -> "${Alarm.amount(this, t)}"
        }
        status.text = (if (on) "Alarm ON daily at %02d:%02d".format(p.getInt("h", 7), p.getInt("m", 0)) else "Alarm OFF") +
            "\nTask: ${t.title} ($detail)"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
