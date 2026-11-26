# Task Alarm (Android)

A daily alarm that keeps ringing until you complete a task you picked:

| Task | How it's checked |
|---|---|
| Scan a QR / barcode | Must match the code you saved (e.g. a sticker on the fridge) |
| Take a photo of something | On-device ML Kit image labeling must find the object you picked from a reference photo |
| Walk N steps | Phone's step detector sensor |
| Solve N math problems | e.g. `37 × 8 + 15` |
| Shake the phone N times | Accelerometer |

Turning the alarm off from the app also requires doing the task.

## Install
Every push builds a debug APK in GitHub Actions (`build` workflow → artifact `task-alarm-apk`).
Download it on your phone, unzip, and install (allow "install unknown apps"). Requires Android 14+.

## Setup
1. Open the app, pick a time and a task, do the task's setup (scan code / take reference photo / enter a number).
2. Tap **Set alarm**. Grant notifications and full-screen alerts when asked (and physical-activity access for steps).
3. Use **Test alarm now** to try it.

## Limitations
- The photo check matches a *category* (e.g. "Refrigerator", "Cup"), not your exact object, so any similar object passes.
- Android always lets you force-stop an app or power off the phone; this app can't prevent that.
- Battery optimisations on some phones may delay alarms; set the app's battery usage to "Unrestricted" if alarms are late.
