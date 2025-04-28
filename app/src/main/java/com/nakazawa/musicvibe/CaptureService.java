package com.nakazawa.musicvibe;

import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import android.os.Build;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import android.app.Notification;                           // Notification クラス  [oai_citation:0‡Android Developers](https://developer.android.com/reference/android/app/Notification?utm_source=chatgpt.com)
import android.media.AudioFormat;
import java.util.Arrays;

public class CaptureService extends Service {
    private static final String TAG = "CaptureService";
    private static final String CHANNEL_ID = "capture_channel";
    private static final int NOTIFICATION_ID = 1001;

    private AudioRecord recorder;
    private HapticEngine haptic;

    // キャプチャ用定数
    private static final int SAMPLE_RATE  = 44100;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING     = AudioFormat.ENCODING_PCM_16BIT;



    @Override
    public void onCreate() {
        super.onCreate();
        // 通知チャンネル登録（API26+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // フォアグラウンドサービス化（MediaProjection タイプ指定を削除）
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Screen Capture Active")
                .setContentText("外部アプリの音声をキャプチャ中")
                .build();

        // 型指定なしで起動
        startForeground(NOTIFICATION_ID, notification);

        // 録音権限チェック（RECORD_AUDIO が必要）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted");
            stopSelf();
            return START_STICKY;
        }

        if (intent == null) {
            Log.e(TAG, "onStartCommand: intent is null, stopping CaptureService");
            stopSelf();
            return START_NOT_STICKY;
        }

        // 画面キャプチャ許可データ取得
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mpMgr = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        MediaProjection mp = mpMgr.getMediaProjection(resultCode, data);

        // 以下、AudioPlaybackCaptureConfiguration 以降はそのまま…
        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(mp)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .build();

        // AudioFormat を明示的に指定（必須） [oai_citation:5‡GitHub](https://github.com/hyochan/react-native-audio-recorder-player/issues/548?utm_source=chatgpt.com)
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_MASK)
                .build();

        // バッファサイズ算出
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
        final int bufferSize = Math.max(minBuf, 2048);  // ラムダで参照するため effectively final [oai_citation:6‡Codementor](https://www.codementor.io/%40wesome/variable-used-in-lambda-expression-should-be-final-or-effectively-final-1urrrjtmac?utm_source=chatgpt.com)

        // AudioRecord 初期化（例外キャッチで安全化） [oai_citation:7‡Android Developers](https://developer.android.com/reference/android/media/AudioRecord.Builder?utm_source=chatgpt.com)
        try {
            recorder = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build();
        } catch (SecurityException e) {
            Log.e(TAG, "AudioRecord init failed", e);
            stopSelf();
            return START_STICKY;
        }

        recorder.startRecording();

        // HapticEngine へ PCM データを逐次渡す
        haptic = new HapticEngine(this, 0);
        new Thread(() -> {
            short[] buffer = new short[bufferSize / 2];
            while (recorder != null
                    && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                int read;
                try {
                    read = recorder.read(buffer, 0, buffer.length);
                } catch (Exception e) {
                    Log.w(TAG, "AudioRecord.read failed, stopping loop", e);
                    break;  // 読み取り中に例外が出たら安全に抜ける
                }
                if (read > 0) {
                    haptic.onPCM(Arrays.copyOf(buffer, read));
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned error: " + read);
                    break;  // エラーコードが返ったら抜ける
                }
            }
        }).start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (recorder != null) {
            recorder.stop();
            recorder.release();
        }
        if (haptic != null) {
            haptic.release();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}