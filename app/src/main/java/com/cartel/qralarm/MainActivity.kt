package com.cartel.qralarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var setupBtn: Button
    private lateinit var amountRow: LinearLayout
    private lateinit var amountBox: EditText
    private lateinit var amountLabel: TextView
    private var selected = Task.QR

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
        amountBox = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; ems = 4 }
        amountRow = LinearLayout(this).apply { addView(amountLabel); addView(amountBox) }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 80, 40, 40)
                addView(status)
                addView(tp)
                addView(TextView(this@MainActivity).apply { text = "\nTask to turn the alarm off:"; textSize = 18f })
                addView(group)
                addView(setupBtn)
                addView(amountRow)
                addView(btn("Set alarm") { arm(tp.hour, tp.minute) })
                addView(btn("Turn off alarm (requires the task)") {
                    if (!p.getBoolean("on", false)) toast("Alarm is already off")
                    else startActivity(Intent(this@MainActivity, RingActivity::class.java).putExtra("disarm", true))
                })
                addView(btn("Test alarm now") {
                    if (!saveSelection()) return@btn
                    startForegroundService(Intent(this@MainActivity, AlarmService::class.java))
                    startActivity(Intent(this@MainActivity, RingActivity::class.java))
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
        setupBtn.visibility = View.GONE
        amountRow.visibility = View.GONE
        when (selected) {
            Task.QR -> { setupBtn.text = "Scan & save the QR/barcode to use"; setupBtn.visibility = View.VISIBLE }
            Task.PHOTO -> { setupBtn.text = "Take a reference photo & pick the object"; setupBtn.visibility = View.VISIBLE }
            Task.STEPS, Task.MATH, Task.SHAKE -> {
                amountLabel.text = when (selected) {
                    Task.STEPS -> "Steps to walk: "
                    Task.MATH -> "Problems to solve: "
                    else -> "Shakes needed: "
                }
                amountBox.setText(Alarm.amount(this, selected).toString())
                amountRow.visibility = View.VISIBLE
            }
        }
    }

    private fun runSetup() {
        if (p.getBoolean("ringing", false)) return
        when (selected) {
            Task.QR -> scan { p.edit().putString("qr", it).apply(); toast("Code saved"); refresh() }
            Task.PHOTO -> takePhoto()
            else -> {}
        }
    }

    @Deprecated("Plain Activity result API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_PHOTO || res != RESULT_OK) return
        toast("Analysing photo…")
        labelLastPhoto { labels ->
            if (labels.isEmpty()) {
                toast("Couldn't recognise anything. Try a clearer, closer photo.")
                return@labelLastPhoto
            }
            val names = labels.take(10).map { "${it.text}  (${(it.confidence * 100).toInt()}%)" }
            AlertDialog.Builder(this)
                .setTitle("What must be in the morning photo?")
                .setItems(names.toTypedArray()) { _, i ->
                    p.edit().putString("label", labels[i].text).apply()
                    toast("Saved: ${labels[i].text}")
                    refresh()
                }
                .show()
        }
    }

    /** Validates and stores the selected task. Returns false if setup is incomplete. */
    private fun saveSelection(): Boolean {
        if (p.getBoolean("ringing", false)) return false
        when (selected) {
            Task.QR -> if (p.getString("qr", null) == null) { toast("Scan your code first"); return false }
            Task.PHOTO -> if (p.getString("label", null) == null) { toast("Take a reference photo first"); return false }
            Task.STEPS, Task.MATH, Task.SHAKE -> {
                val n = amountBox.text.toString().toIntOrNull()
                if (n == null || n < 1 || n > 10000) { toast("Enter a number from 1 to 10000"); return false }
                p.edit().putInt("amount_$selected", n).apply()
            }
        }
        if (selected == Task.STEPS &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
            toast("Allow physical activity access (needed to count steps), then tap again")
            return false
        }
        p.edit().putString("task", selected.name).apply()
        return true
    }

    private fun arm(h: Int, m: Int) {
        if (!saveSelection()) return
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.canUseFullScreenIntent()) {
            toast("Allow full-screen alerts, then tap again")
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            return
        }
        p.edit().putInt("h", h).putInt("m", m).putBoolean("on", true).apply()
        Alarm.schedule(this)
        refresh()
        toast("Alarm set")
    }

    private fun refresh() {
        val on = p.getBoolean("on", false)
        val t = Alarm.task(this)
        val detail = when (t) {
            Task.QR -> if (p.getString("qr", null) != null) "code saved" else "no code saved"
            Task.PHOTO -> "photo of: " + (p.getString("label", null) ?: "not set")
            else -> "${Alarm.amount(this, t)}"
        }
        status.text = (if (on) "Alarm ON daily at %02d:%02d".format(p.getInt("h", 7), p.getInt("m", 0)) else "Alarm OFF") +
            "\nTask: ${t.title} ($detail)"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
