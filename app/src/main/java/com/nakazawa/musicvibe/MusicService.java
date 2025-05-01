package com.nakazawa.musicvibe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class MusicService extends Service {

    private static final String TAG = "MusicService";
    private static final int NOTI_ID = 1;

    private MediaPlayer player;
    private Visualizer visualizer;
    private HapticEngine haptic;

    private final IBinder binder = new ServiceBinder();
    private boolean isMuted = false;
    private boolean advancedHapticsEnabled = false;
    private boolean isPrepared = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        player = new MediaPlayer();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setHapticChannelsMuted(false)
                .build();
        player.setAudioAttributes(attrs);

        player.setOnCompletionListener(mp -> {
            stopSelf();
            updateNotification("停止しました");
        });

        startForeground(NOTI_ID, buildNotification("準備中…"));
    }

    @Override
    public void onDestroy() {
        releaseVisualizer();
        if (haptic != null) haptic.release();
        if (player != null) {
            player.stop();
            player.release();
            isPrepared = false;
        }
        super.onDestroy();
    }

    public void load(String uriStr) {
        try {
            player.reset();
            player.setDataSource(this, Uri.parse(uriStr));
            player.prepare();
            isPrepared = true;
            player.start();
            updateNotification("再生中…");

            int sid = player.getAudioSessionId();
            rebuildHapticEngine(sid);
        } catch (Exception e) {
            Log.e(TAG, "load error", e);
            updateNotification("エラーが発生しました");
        }
    }

    public void togglePlayPause() {
        if (player.isPlaying()) {
            player.pause();
            updateNotification("一時停止中…");
            if (haptic != null) haptic.pauseHaptics();
        } else {
            player.start();
            updateNotification("再生中…");
            if (haptic != null) haptic.resumeHaptics();
        }
    }

    public void toggleMute() {
        isMuted = !isMuted;
        player.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f);
        updateNotification(isMuted ? "ミュート中…" : "再生中…");
    }

    public void seekTo(float pct) {
        if (player.getDuration() > 0) {
            player.seekTo((int) (pct * player.getDuration()));
        }
    }

    public float getDuration() {
        try {
            return (player != null) ? player.getDuration() : 0f;
        } catch (IllegalStateException e) {
            Log.w(TAG, "getDuration called in wrong state", e);
            return 0f;
        }
    }

    public float getPosition() {
        try {
            return (player != null) ? player.getCurrentPosition() : 0f;
        } catch (IllegalStateException e) {
            Log.w(TAG, "getPosition called in wrong state", e);
            return 0f;
        }
    }

    public boolean isPlaying() { return player.isPlaying(); }

    public void updateHapticScale() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        float scale = am.getStreamVolume(AudioManager.STREAM_MUSIC) /
                (float) am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (haptic != null) haptic.setUserScale(scale);
    }

    private void releaseVisualizer() {
        if (visualizer != null) {
            try { visualizer.setEnabled(false); } catch (Exception ignore) {}
            visualizer.release();
            visualizer = null;
        }
    }

    private void attachVisualizer(int sessionId) {
        if (!advancedHapticsEnabled) return;
        releaseVisualizer();
        try {
            visualizer = new Visualizer(sessionId);
            // setCaptureSize は STATE_INITIALIZED でのみ有効だが例外が出る場合はスキップ
            int maxSize = Visualizer.getCaptureSizeRange()[1];
            try {
                visualizer.setCaptureSize(maxSize);
            } catch (IllegalStateException e) {
                Log.w(TAG, "Visualizer setCaptureSize skipped, wrong state", e);
            }
            visualizer.setDataCaptureListener(
                    new Visualizer.OnDataCaptureListener() {
                        @Override public void onWaveFormDataCapture(Visualizer v, byte[] wf, int sr) {}
                        @Override public void onFftDataCapture(Visualizer v, byte[] fft, int sr) {
                            if (haptic != null) haptic.onFFT(fft);
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true
            );
            visualizer.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "Visualizer init error", e);
            releaseVisualizer();
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, "music_haptic")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("MusicVibe")
                .setContentText(text)
                .setColor(Color.MAGENTA)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTI_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                "music_haptic", "MusicVibe",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private void rebuildHapticEngine(int sessionId) {
        releaseVisualizer();
        if (haptic != null) haptic.release();
        boolean forceFallback = !advancedHapticsEnabled;
        haptic = new HapticEngine(this, sessionId, forceFallback);
        updateHapticScale();
        attachVisualizer(sessionId);
    }

    public class ServiceBinder extends Binder {
        public boolean isPrepared()           { return MusicService.this.isPrepared; }
        public void load(String uri)          { MusicService.this.load(uri); }
        public void togglePlayPause()         { MusicService.this.togglePlayPause(); }
        public void toggleMute()              { MusicService.this.toggleMute(); }
        public void seekTo(float p)           { MusicService.this.seekTo(p); }
        public float  getDuration()           { return MusicService.this.getDuration(); }
        public float  getPosition()           { return MusicService.this.getPosition(); }
        public boolean isPlaying()            { return MusicService.this.isPlaying(); }
        public void updateHapticScale()       { MusicService.this.updateHapticScale(); }

        public void setAdvancedHapticsEnabled(boolean enabled) {
            advancedHapticsEnabled = enabled;
            rebuildHapticEngine(player.getAudioSessionId());
            Log.d(TAG, "Advanced Haptics Enabled → " + enabled);
        }

        public void startBackgroundHaptics()  { startBackgroundHaptics(); }
        public void stopBackgroundHaptics()   { stopBackgroundHaptics(); }
        public void pauseHaptics()            { if (haptic != null) haptic.pauseHaptics(); }
        public void resumeHaptics()           { if (haptic != null) haptic.resumeHaptics(); }
    }

    public void startBackgroundHaptics() {
        releaseVisualizer();
        if (haptic != null) haptic.release();
        haptic = new HapticEngine(this, 0, !advancedHapticsEnabled);
        updateHapticScale();
        Log.d(TAG, "Background haptics started (session 0)");
    }

    public void stopBackgroundHaptics() {
        if (haptic != null) {
            haptic.release();
            haptic = null;
        }
        int sid = player.getAudioSessionId();
        rebuildHapticEngine(sid);
        Log.d(TAG, "Background haptics stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
}
