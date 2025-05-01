package com.nakazawa.musicvibe;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.KeyEvent;

import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import android.database.Cursor;
import android.Manifest;
import androidx.core.app.ActivityCompat;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import android.os.Handler;
import android.media.audiofx.HapticGenerator;
import android.content.Intent;
import android.os.IBinder;
import androidx.appcompat.widget.SwitchCompat;
import android.view.View;
import android.content.Context;
import android.media.projection.MediaProjectionManager;
import android.widget.Button;


public class MainActivity extends AppCompatActivity {

    // ファイル選択ランチャーと保留 URI
    private ActivityResultLauncher<String[]> filePicker;
    private Uri pendingLoadUri = null;
    // サービスバインド状態と UI ボタン
    private boolean isBound = false;
    private Button btnLoad;

    private Button btnPlay, btnMute, btnBackground;
    private TextView txtTitle;
    private SeekBar seek;
    private MusicService.ServiceBinder binder;
    private static final int REQUEST_RECORD = 1001;
    private Handler updateHandler;
    private static final int REQUEST_CODE_CAPTURE_PERM = 1001;
    private static final int REQUEST_BACKGROUND = 2002;
    private boolean isBackgroundMode = false;



    /** Activity ↔ Service 接続 */
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder   = (MusicService.ServiceBinder) service;
            isBound  = true;

            // ① ボタンを有効化
            btnLoad.setEnabled(true);  // ◆初回グレーアウトの解消 [oai_citation:7‡Stack Overflow](https://stackoverflow.com/questions/14412238/android-how-to-enable-button-when-service-complete-its-task?utm_source=chatgpt.com)

            // ② 保留中のファイルがあればロード
            if (pendingLoadUri != null) {
                loadTrack(pendingLoadUri);
                pendingLoadUri = null;
            }

            // ③ UI 更新ループを開始
            observePlayer();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };


    private void registerFilePicker() {
        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    // サービス接続済みなら即ロード、未接続なら保留
                    if (isBound) {
                        loadTrack(uri);
                    } else {
                        pendingLoadUri = uri;
                    }
                }
        );
    }

    /** mp3 選択 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ① ファイルピッカー登録 (最優先)
        registerFilePicker();  // ◆これがないと uri が未定義になります

        // ② 権限チェック → 許可済ならサービス起動
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{ Manifest.permission.RECORD_AUDIO },
                    REQUEST_RECORD
            );
        } else {
            startAndBindService();
        }

        setContentView(R.layout.activity_main);

        // ③ ボタン初期化：サービス接続完了まで押せないようにする
        btnLoad = findViewById(R.id.btnLoad);
        btnLoad.setEnabled(false);  // ◆ここでグレーアウト
        btnLoad.setOnClickListener(v -> {
            filePicker.launch(new String[]{"audio/mpeg"});
        });

        btnPlay = findViewById(R.id.btnPlay);
        btnMute = findViewById(R.id.btnMute);
        txtTitle = findViewById(R.id.txtTitle);
        seek    = findViewById(R.id.seek);

        // ★「Advanced Haptics Generator」用スイッチを追加
        // ② Advanced Haptics Generator スイッチ取得
        SwitchCompat switchAdvanced = findViewById(R.id.switchAdvanced);

        // ③ HapticGenerator 対応チェック
        boolean hgAvailable = HapticGenerator.isAvailable();
        switchAdvanced.setChecked(false);
        switchAdvanced.setEnabled(hgAvailable);

        // ④ スイッチ操作時のリスナー登録
        switchAdvanced.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (isBound) {
                        binder.setAdvancedHapticsEnabled(isChecked);
                    }
                }
        );

        // ③ ファイル選択・再生・ミュートリスナーは従来どおり
        btnLoad.setOnClickListener(v -> filePicker.launch(new String[]{"audio/mpeg"}));
        btnPlay.setOnClickListener(v -> {
            if (isBound) binder.togglePlayPause();
        });
        btnMute.setOnClickListener(v -> {
            if (isBound) binder.toggleMute();
        });

        // ④ シークバー同期も従来どおり
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && isBound) binder.seekTo(progress / 1000f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // ⑤ 「Advanced Haptics Generator」スイッチの変更時にサービスへ状態を通知
        switchAdvanced.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isBound) {
                // ServiceBinder にメソッドを追加して制御（要実装）
                binder.setAdvancedHapticsEnabled(isChecked);
            }
        });

        // ▼ BackGround ボタン取得
        btnBackground = findViewById(R.id.btnBackground);

// ▼ Advanced Haptics スイッチの初期表示制御
        btnBackground.setVisibility(switchAdvanced.isChecked() ? View.GONE : View.VISIBLE);

// ▼ スイッチの変更時に表示切替
        switchAdvanced.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 既存コード…
            if (isBound) {
                binder.setAdvancedHapticsEnabled(isChecked);
            }
            // BackGround ボタン表示制御
            btnBackground.setVisibility(isChecked ? View.GONE : View.VISIBLE);
        });

// ▼ BackGround ボタン押下時の動作
        setupBackgroundButton();

        // ⑥ プレーヤーの監視開始
        observePlayer();
    }

    private void requestScreenCapture() {
        MediaProjectionManager mpMgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(
                mpMgr.createScreenCaptureIntent(),
                REQUEST_CODE_CAPTURE_PERM
        );
    }

    private void setupBackgroundButton() {
        btnBackground = findViewById(R.id.btnBackground);
        btnBackground.setText(R.string.bg_start);

        btnBackground.setOnClickListener(v -> {
            if (!isBackgroundMode) {
                // ① RECORD_AUDIO の許可確認／要求
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                            this,
                            new String[]{ Manifest.permission.RECORD_AUDIO },
                            REQUEST_BACKGROUND
                    );
                } else {
                    // ② スクリーンキャプチャ許可を要求 → onActivityResult で開始
                    requestScreenCapture();
                }
            } else {
                // 停止処理
                binder.stopBackgroundHaptics();
                stopService(new Intent(this, CaptureService.class));
                btnBackground.setText(R.string.bg_start);
                isBackgroundMode = false;
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_CAPTURE_PERM) {
            if (resultCode == RESULT_OK && data != null) {
                // CaptureService を起動してバックグラウンド処理を開始
                Intent svc = new Intent(this, CaptureService.class)
                        .putExtra("resultCode", resultCode)
                        .putExtra("data", data);
                ContextCompat.startForegroundService(this, svc);

                // ボタン表示を StopBackGround に切り替え
                btnBackground.setText(R.string.bg_stop);
                isBackgroundMode = true;
            } else {
                Toast.makeText(this,
                        "キャプチャ許可が得られませんでした",
                        Toast.LENGTH_SHORT
                ).show();
            }
            return;
        }

    }

    /** サービスの起動＆バインドをまとめて呼び出し */
    private void startAndBindService() {
        Intent i = new Intent(this, MusicService.class);
        ContextCompat.startForegroundService(this, i);
        bindService(i, connection, BIND_AUTO_CREATE);
    }

    /** 権限ダイアログの結果ハンドリング */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 音声キャプチャの許可が降りたらサービスを開始
                startAndBindService();
            } else {
                Toast.makeText(this,
                        "録音権限がないとハプティクスが動作しません",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    /** ファイル名取得＋サービスへ送信 */
    private void loadTrack(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    txtTitle.setText(c.getString(idx));
                }
            }
        }
        if (isBound) binder.load(uri.toString());
    }

    /** SeekBar と Player の同期を UI スレッドで更新 */
    private void observePlayer() {
        updateHandler = new Handler(Looper.getMainLooper());
        Runnable uiUpdateRunnable = new Runnable() {
            @Override public void run() {
                if (isBound && binder.isPrepared()) {             // ← ここを追加
                    float duration = binder.getDuration();        // 安全に呼べる
                    if (duration > 0f) {
                        float pos = binder.getPosition() / duration;
                        seek.setProgress((int) (pos * 1000));
                        btnPlay.setText(binder.isPlaying() ? "⏸" : "▶︎");
                    }
                }
                updateHandler.postDelayed(this, 200);
            }
        };
        updateHandler.postDelayed(uiUpdateRunnable, 200);
    }

    /** 音量キーで振動スケール調整 */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // まずストリーム音量変更をデフォルト処理に任せる
            boolean handled = super.onKeyUp(keyCode, event);
            // そのあとで最新の音量を取得してスケール更新
            if (isBound) binder.updateHapticScale();
            return handled;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isBound && binder != null) {
            binder.pauseHaptics();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isBound && binder != null) {
            binder.resumeHaptics();
        }
    }

    @Override
    protected void onDestroy() {
        // サービスのバインド解除
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        // UI 更新用ハンドラのコールバック解除
        if (updateHandler != null) {
            updateHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }
}