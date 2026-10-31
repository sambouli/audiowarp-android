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
├── audio/Recorder.kt
├── player/PlayerEngine.kt
├── data/ClipStore.kt
└── ui/             # Jetpack Compose (ViewModel + StateFlow, WaveformCanvas)
```

`app/src/compilestubs/java/android/media/AudioTrack.java` is a **compile-time-only** stub
(added via `sourceSets`, never dexed — verified absent from the APK) that works around an
SDK-surface quirk when compiling against API 35.

## Build

```bash
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # ~55 JUnit tests (DSP math, WAV IO, state)
```

Requirements: JDK 17+, Android SDK 35 (`ANDROID_HOME` set), AGP 8.7.3, Kotlin 2.0.21.

## Install

Sideload the debug APK (minSdk 26, targetSdk 35):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds re-sign with the debug keystore for personal sideloading.
