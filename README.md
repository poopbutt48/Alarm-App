# Task Alarm (Android)

A daily alarm that keeps ringing until you complete a task you picked:

| Task | How it's checked |
|---|---|
| Show the AI something (e.g. "a milk jug") | Live camera; tap CHECK and on-device **Gemini Nano** (via Android AICore) judges the frame against what you typed |
| Push-ups / squats | Live camera + on-device **ML Kit pose detection** tracks your elbows/knees and counts reps |
| Scan a QR / barcode | Must match the code you saved (e.g. a sticker on the fridge) |
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

All AI runs on the phone; no images leave the device.

Gemini Nano only works on [supported devices](https://developers.google.com/ml-kit/genai#device-support)
(the Galaxy S25/S26 series is listed). The app checks this and lets you download the model from the setup screen.

## Limitations
- The AI can be wrong in both directions. After 5 failed AI checks (or 3 minutes of rep tracking) a
  "solve 10 math problems instead" escape appears so you can never be stuck with a ringing alarm.
- If the AI isn't available when the alarm rings, it falls back to math problems.
- Rep counting needs your whole body visible from the side with decent light.
- The ML Kit GenAI (Gemini Nano) API is in beta and may change.
- Android always lets you force-stop an app or power off the phone; this app can't prevent that.
- Battery optimisations on some phones may delay alarms; set the app's battery usage to "Unrestricted" if alarms are late.
