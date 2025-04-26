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

public class MainActivity extends AppCompatActivity {

    private Button btnPlay, btnMute, btnLoad;
    private TextView txtTitle;
    private SeekBar seek;
    private boolean isBound = false;
    private MusicService.ServiceBinder binder;
    private static final int REQUEST_RECORD = 1001;
    private boolean advancedHapticsEnabled = false;

    /** Activity ↔ Service 接続 */
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (MusicService.ServiceBinder) service;
            isBound = true;
            observePlayer();
        }
        @Override public void onServiceDisconnected(ComponentName name) { isBound = false; }
    };

    /** mp3 選択 */
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) loadTrack(uri);
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ① 権限チェックとサービス起動は従来どおり
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

        // ② UI コンポーネントの取得
        btnLoad = findViewById(R.id.btnLoad);
        btnPlay = findViewById(R.id.btnPlay);
        btnMute = findViewById(R.id.btnMute);
        txtTitle = findViewById(R.id.txtTitle);
        seek    = findViewById(R.id.seek);

        // ★「Advanced Haptics Generator」用スイッチを追加
        // ② Advanced Haptics Generator スイッチ取得
        SwitchCompat switchAdvanced = findViewById(R.id.switchAdvanced);  // XML 定義済み Switch  [oai_citation:2‡Android Developers](https://developer.android.com/reference/android/widget/Switch?utm_source=chatgpt.com)

        // ③ HapticGenerator 対応チェック
        boolean hgAvailable = HapticGenerator.isAvailable();        // HG サポート端末のみ true  [oai_citation:3‡Android Developers](https://developer.android.com/reference/android/media/audiofx/HapticGenerator?utm_source=chatgpt.com)
        switchAdvanced.setChecked(false);                           // デフォルト OFF  [oai_citation:4‡Android Developers](https://developer.android.com/reference/android/preference/SwitchPreference?utm_source=chatgpt.com)
        switchAdvanced.setEnabled(hgAvailable);                     // 非対応時はグレーアウト  [oai_citation:5‡Android Developers](https://developer.android.com/reference/android/view/View?utm_source=chatgpt.com)

        // ④ スイッチ操作時のリスナー登録
        switchAdvanced.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (isBound) {
                        binder.setAdvancedHapticsEnabled(isChecked);    // サービス側で振動生成モードを切替  [oai_citation:6‡Android Developers](https://developer.android.com/reference/android/widget/CompoundButton.OnCheckedChangeListener?utm_source=chatgpt.com)
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
                binder.setAdvancedHapticsEnabled(isChecked);                                // 切り替え  [oai_citation:1‡Microsoft Learn](https://learn.microsoft.com/en-us/dotnet/api/android.media.audiofx.hapticgenerator.isavailable?view=net-android-35.0&utm_source=chatgpt.com)
            }
        });

        // ⑥ プレーヤーの監視開始
        observePlayer();
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
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isBound) {
                    try {
                        float duration = binder.getDuration();
                        if (duration > 0) {
                            float pos = binder.getPosition() / duration;
                            seek.setProgress((int) (pos * 1000));
                            btnPlay.setText(binder.isPlaying() ? "⏸" : "▶︎");
                        }
                    } catch (IllegalStateException e) {
                    }
                }
                h.postDelayed(this, 200);
            }
        }, 200);
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

    @Override protected void onDestroy() {
        if (isBound) unbindService(connection);
        super.onDestroy();
    }
}