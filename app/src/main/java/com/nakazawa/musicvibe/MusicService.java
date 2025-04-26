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

    private MediaPlayer    player;
    private Visualizer     visualizer;
    private HapticEngine   haptic;

    private final IBinder binder = new ServiceBinder();
    private boolean isMuted = false;
    private boolean advancedHapticsEnabled = false;

    /*──────────────────────────
     * Service lifecycle
     *──────────────────────────*/
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // MediaPlayer を生成し、ハプティック用の AudioAttributes を設定
        player = new MediaPlayer();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setHapticChannelsMuted(false)           // haptic チャンネルを有効化
                .build();
        player.setAudioAttributes(attrs);

        // 再生完了時の後始末
        player.setOnCompletionListener(mp -> {
            stopSelf();
            updateNotification("停止しました");
        });

        startForeground(NOTI_ID, buildNotification("準備中…"));
    }

    @Override
    public void onDestroy() {
        if (visualizer != null) visualizer.release();
        if (haptic     != null) haptic.release();
        if (player     != null) {
            player.stop();
            player.release();
        }
        super.onDestroy();
    }

    /*──────────────────────────
     * Public control methods
     *──────────────────────────*/
    public void load(String uriStr) {
        try {
            player.reset();
            player.setDataSource(this, Uri.parse(uriStr));
            player.prepare();
            player.start();
            updateNotification("再生中…");

            int sessionId = player.getAudioSessionId();
            if (haptic == null) {
                haptic = new HapticEngine(this, sessionId);  // HapticGenerator を内部で初期化
            }
            attachVisualizer(sessionId);

            // ← ここを追加：初回再生時にも音量スケールを反映させる
            updateHapticScale();

        } catch (Exception e) {
            Log.e(TAG, "load error", e);
            updateNotification("エラーが発生しました");
        }
    }

    public void togglePlayPause() {
        if (player.isPlaying()) {
            player.pause();
            updateNotification("一時停止中…");
        } else {
            player.start();
            updateNotification("再生中…");
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
            // player が null または準備前の場合、例外を防ぐ
            return (player != null) ? player.getDuration() : 0f;
        } catch (IllegalStateException e) {
            Log.w(TAG, "getDuration called in wrong state", e);
            return 0f;  // 不正な状態では 0 を返却
        }
    }

    // 同様に getPosition() も安全化しておくとより堅牢です
    public float getPosition() {
        try {
            return (player != null) ? player.getCurrentPosition() : 0f;
        } catch (IllegalStateException e) {
            Log.w(TAG, "getPosition called in wrong state", e);
            return 0f;
        }
    }
    public boolean isPlaying()    { return player.isPlaying(); }

    public void updateHapticScale() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        float scale = am.getStreamVolume(AudioManager.STREAM_MUSIC) /
                (float) am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (haptic != null) haptic.setUserScale(scale);
    }

    /*──────────────────────────
     * Visualizer & Haptic
     *──────────────────────────*/
    private void attachVisualizer(int sessionId) {
        if (sessionId <= 0) {
            Log.e(TAG, "Invalid audioSessionId: " + sessionId);
            return;
        }
        if (visualizer != null) visualizer.release();

        try {
            visualizer = new Visualizer(sessionId);
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
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
            Log.d(TAG, "Visualizer attached to session " + sessionId);
        } catch (Exception e) {
            Log.e(TAG, "Visualizer init error", e);
        }
    }

    /*──────────────────────────
     * Notification helpers
     *──────────────────────────*/
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

    /*──────────────────────────
     * Binder
     *──────────────────────────*/
    public class ServiceBinder extends Binder {
        public void load(String uri)          { MusicService.this.load(uri); }
        public void togglePlayPause()         { MusicService.this.togglePlayPause(); }
        public void toggleMute()              { MusicService.this.toggleMute(); }
        public void seekTo(float p)           { MusicService.this.seekTo(p); }
        public float  getDuration()           { return MusicService.this.getDuration(); }
        public float  getPosition()           { return MusicService.this.getPosition(); }
        public boolean isPlaying()            { return MusicService.this.isPlaying(); }
        public void updateHapticScale()       { MusicService.this.updateHapticScale(); }
        public void setAdvancedHapticsEnabled(boolean enabled) { MusicService.this.setAdvancedHapticsEnabled(enabled);
        }
    }

    void setAdvancedHapticsEnabled(boolean enabled) {
        this.advancedHapticsEnabled = enabled;
        Log.d(TAG, "Advanced Haptics Enabled → " + enabled);
    }

    /*──────────────────────────
     * Binder entry
     *──────────────────────────*/
    @Nullable @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
}