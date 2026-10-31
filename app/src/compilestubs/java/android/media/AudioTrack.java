package android.media;

/**
 * COMPILE-TIME STUB — DO NOT SHIP.
 * The platform android.jar for API 35 in this SDK installation exposes a truncated
 * AudioTrack surface (javap sees the real methods, kotlinc's class reader does not),
 * so we shadow it with this source stub to compile. This file is compiled SEPARATELY
 * (see app/build.gradle.kts: compileStubClasses) and its output is placed only on
 * the Kotlin compile classpath — NEVER dexed into the APK. At runtime the REAL
 * framework android.media.AudioTrack from the device ROM is used.
 */
public class AudioTrack {
    public static final int MODE_STATIC = 0;
    public static final int MODE_STREAM = 1;
    public static final int PLAYSTATE_STOPPED = 1;
    public static final int PLAYSTATE_PAUSED = 2;
    public static final int PLAYSTATE_PLAYING = 3;
    public static final int WRITE_BLOCKING = 0;
    public static final int WRITE_NON_BLOCKING = 1;

    public AudioTrack(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes, int mode)
            throws IllegalArgumentException {}

    public int write(float[] audioData, int offsetInFloats, int sizeInFloats, int writeMode)
            throws IllegalArgumentException { return 0; }

    public void setNotificationMarkerPosition(int markerInFrames) {}

    public interface OnPlaybackPositionUpdateListener {
        void onMarkerReached(AudioTrack track);

        void onPeriodicNotification(AudioTrack track);
    }

    public void setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener listener) {}

    public int setVolume(float leftVolume) { return 0; }

    public void play() throws IllegalStateException {}

    public void stop() throws IllegalStateException {}

    public int getPlayState() { return PLAYSTATE_STOPPED; }

    public void release() {}
}
