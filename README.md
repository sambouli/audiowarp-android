# AudioWarp

Native Android audio-modulation app: record from the microphone, warp the clip with one of 12 effects, play it back, save and share.

## Features

- **Recording** — 16 kHz mono PCM via `AudioRecord`, saved as canonical WAV files.
- **Player** — play / pause / seek / loop with a live waveform canvas (neon cyberpunk dark theme).
- **12 effects** (pure-Kotlin DSP, no native code):
  1. Reverb (Schroeder hall)
  2. Robot (ring modulation)
  3. Ghost (pitch-shifted feedback delay)
  4. Chipmunk (high pitch)
  5. Monster (low pitch)
  6. Echo (feedback delay)
  7. Megaphone (bandpass)
  8. Underwater (low-pass)
  9. Reverse
  10. Radio (drive + bandpass + noise)
  11. Alien (flanger + tremolo)
  12. Slow-Mo (OLA time stretch)
- **Export** — save modulated clips to app storage and share via the system sheet.

## Architecture

```
app/src/main/java/com/sambouli/audiowarp/
├── dsp/            # Pure Kotlin DSP core (no android.* imports, JVM unit-tested)
│   ├── Dsp.kt      #   PCM<->float, filters, reverb, echo, soft-clip
│   ├── Pitch.kt    #   resample, pitch shift, OLA time stretch, NCCF pitch estimator
│   ├── Effects.kt  #   12-effect engine
│   └── WavIO.kt    #   RIFF/WAVE reader & writer
├── audio/Recorder.kt            # AudioRecord capture, audio-priority thread
├── player/PlayerEngine.kt       # AudioTrack playback: static clips, streaming fallback
├── data/ClipStore.kt
└── ui/             # Jetpack Compose (ViewModel + StateFlow, WaveformCanvas)
```

## AudioTrack: no stubs, no wrapper (read before touching playback)

There is no `android.media.AudioTrack` stub in this project, and none may be added. An earlier
revision carried one so kotlinc could compile a four-argument `AudioTrack` constructor. That
constructor does not exist: API 35 exports
`AudioTrack(AudioAttributes, AudioFormat, int bufferSizeInBytes, int mode, int notificationThresholdInFrames)`.
Compiling against a hand-written stub hid the mismatch from the compiler, so the APK shipped a call
to a signature no device ROM exports. The first press of play resolved it as `NoSuchMethodError`,
which is an `Error` and so walked straight past the `catch (_: Exception)` around the whole block —
the process died on every play, warped clip or not. The unit suite could not see it because
Robolectric shadows `AudioTrack` and never runs the real constructor.

Three rules follow from that:

- Call only constructors and methods confirmed against
  `$ANDROID_HOME/platforms/<api>/android.jar` with `javap -classpath <jar> -public android.media.AudioTrack`.
  If the SDK appears to be missing something, it is the call site that is wrong, not the SDK.
- Reach an overload that only exists on newer releases behind `Build.VERSION.SDK_INT`
  (`Build.VERSION_CODES.TIRAMISU` and friends), never behind a stub.
- Anything a gesture reaches must not let a `Throwable` out. A Compose click handler runs on the UI
  thread, so an escaped `Error` there is a dead app rather than a failed tap.

`app/build.gradle.kts` fails the build if `app/src/main` grows an `android/` or `java/` directory.

## Build

```bash
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 62 JUnit tests (DSP math, WAV IO, player contract)
```

Requirements: JDK 17+, Android SDK 35 (`ANDROID_HOME` set), AGP 8.7.3, Kotlin 2.0.21.

## Install

Sideload the debug APK (minSdk 26, targetSdk 35):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds re-sign with the debug keystore for personal sideloading.
