package com.nakazawa.musicvibe;

import android.content.Context;
import android.media.audiofx.HapticGenerator;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.util.concurrent.ArrayBlockingQueue;
import android.os.CombinedVibration;

/**
 * HapticEngine
 * ──────────────────────────────────────────────────────────────
 * ・アプリ内再生（audioSession > 0）
 *     ├─ HapticGenerator が使える端末 → そのまま HG
 *     └─ 使えない端末 → Visualizer + Primitive 合成
 * ・BackGround 再生（audioSession == 0）
 *     └─ AudioRecord から PCM を受け取り，本 Runnable で滑らか振動
 */
@RequiresApi(api = Build.VERSION_CODES.S)
public class HapticEngine {

    /*==== 共通定数 =====================================================*/
    private static final String TAG = "HapticEngine";
    private static final int    FRAME_MS      = 20;   // Visualizer 版用（従来どおり）
    private static final float  DEFAULT_SCALE = 1.2f; // 音量スケール初期値

    /*==== BackGround 専用定数 ==========================================*/
    private static final int    FRAME_MS_BG   = 10;   // 1 フレーム 30 ms
    private static final int    MIN_AMPLITUDE = 15;   // 振幅下限
    private static final double GATE_MARGIN   = 0.02; // ノイズ床 + 2 %
    /*==== フィールド ===================================================*/
    private final Vibrator vibrator;
    private final ArrayBlockingQueue<short[]> pcmQueue = new ArrayBlockingQueue<>(64);

    private boolean useHg;
    private HapticGenerator hg;
    private HandlerThread thread;
    private Handler handler;

    private float  userScale    = DEFAULT_SCALE;
    private double noiseFloor   = 0.0; // ゲート用 EMA
    private double smoothedNorm = 0.0; // 追加平滑化用
    private int lastBgAmp = 0;
    private static final int AMP_DELTA       = 5;   // 振幅差の最小変化量
    private boolean bgLooping = false;
    private volatile short[] latestPcm = new short[0];
    private final Context mContext;
    private final int     mAudioSessionId;
    private volatile boolean isPaused = false;
    private Visualizer visualizer;


    public HapticEngine(Context context, int audioSessionId) {
        this.mContext        = context;   // ← 初期化
        this.mAudioSessionId = audioSessionId; // ← 初期化
        this.vibrator        =
                (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
    }


    /*==================================================================*/

    public HapticEngine(Context ctx, int audioSession, boolean forceFallback) {
        // ① mContext, mAudioSessionId, vibrator はここで初期化される
        this(ctx, audioSession);

        // ② HapticGenerator 利用可否を判定
        boolean hgAvailable = !forceFallback
                && HapticGenerator.isAvailable()
                && audioSession > 0;
        this.useHg = false;

        if (hgAvailable) {
            try {
                hg = HapticGenerator.create(audioSession);
                hg.setEnabled(true);
                useHg = true;
                return;
            } catch (Exception e) {
                Log.w(TAG, "HapticGenerator init failed, fallbackへ", e);
            }
        }

        // ③ フォールバック経路
        if (audioSession == 0) {
            thread = new HandlerThread("HapticEngineBgThread");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.postDelayed(processRmsRunnableBg, FRAME_MS_BG);
        } else {
            setupPrimitiveVisualizer();
        }
    }

    /*-----------------------------------------------------------------------
     * BackGround 専用：滑らか振動 Runnable
     *---------------------------------------------------------------------*/
    private final Runnable processRmsRunnableBg = new Runnable() {
        @Override public void run() {
            // 1. PCMデータをキューから取得
            short[] pcm = pcmQueue.poll();
            if (pcm == null) {
                // データ無 -> 次フレーム予約
                handler.postDelayed(this, FRAME_MS_BG);
                return;
            }

            // 2. 擬似3バンドRMSを計算
            int n = pcm.length / 3;
            double bass   = rmsOf(pcm, 0, n);
            double melody = rmsOf(pcm, n, 2 * n);
            double other  = rmsOf(pcm, 2 * n, pcm.length);
            double weighted = 1.5 * bass + 2.5 * melody + 0.25 * other;
            double x = Math.min(1.0, weighted / 32768.0);

            // 3. ノイズゲート判定 (更新前の noiseFloor を使用)
            double threshold = noiseFloor + GATE_MARGIN;
            boolean gateOpen = (x >= threshold);

            // 4. ノイズ床をEMAでゆっくり更新
            double target = gateOpen ? threshold : x;
            noiseFloor = noiseFloor * 0.99 + target * 0.01;

            if (!gateOpen) {
                // ゲート閉 -> 振動停止 or 継続無し
                if (bgLooping) {
                    vibrator.cancel();
                    bgLooping = false;
                }
                handler.postDelayed(this, FRAME_MS_BG);
                return;
            }

            // 5. 非線形圧縮 + 追加ロー・パス平滑化
            double t = 0.25;
            double norm = (x < t)
                    ? Math.pow(x / t, 3.5) * 0.35
                    : 0.4 + Math.pow((x - t) / (1 - t), 7) * 0.6;
            smoothedNorm = 0.3 * norm + 0.7 * smoothedNorm;

            // 6. 振動開始 or 更新
            int amp = (int) (smoothedNorm * userScale * 255);
            if (amp >= MIN_AMPLITUDE) {
                if (!bgLooping || Math.abs(amp - lastBgAmp) > AMP_DELTA) {
                    long[] timings    = {0, FRAME_MS_BG};
                    int[]  amplitudes = {0, amp};
                    // 一度だけ設定しループ再生 (repeat=0)
                    VibrationEffect effect =
                            VibrationEffect.createWaveform(timings, amplitudes, 0);
                    vibrator.vibrate(effect);
                    bgLooping = true;
                    lastBgAmp = amp;
                }
            } else if (bgLooping) {
                vibrator.cancel();
                bgLooping = false;
            }

            // 7. 次フレーム予約
            handler.postDelayed(this, FRAME_MS_BG);
        }
    };

    /*==== Visualizer → Primitive 経路（従来どおり） ==================*/
    private void setupPrimitiveVisualizer() {
        try {
            // ① もし前回の visualizer が生きていれば必ず無効化＆解放
            if (visualizer != null) {
                visualizer.setEnabled(false);   // 停止
                visualizer.release();           // リソース解放
            }

            visualizer = new Visualizer(mAudioSessionId);
            // ③ STATE_INITIALIZED の状態なのでキャプチャサイズ設定OK
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            visualizer.setDataCaptureListener(
                    new Visualizer.OnDataCaptureListener() {
                        @Override
                        public void onWaveFormDataCapture(Visualizer v, byte[] wf, int sr) {
                            if (wf == null) return;
                            short[] pcm = new short[wf.length];
                            for (int i = 0; i < wf.length; i++) {
                                pcm[i] = (short) (((wf[i] & 0xFF) - 128) << 8);
                            }
                            latestPcm = pcm;
                        }

                        @Override
                        public void onFftDataCapture(Visualizer v, byte[] fft, int sr) {
                            // 一時停止中は振動処理に入らない
                            if (isPaused) return;
                            if (fft != null && latestPcm.length > 0) {
                                processFftPrimitives(latestPcm, fft, mAudioSessionId);
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,   // waveform
                    true    // fft
            );
            visualizer.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "Visualizer init error", e);
        }
    }

    public void pauseHaptics() {
        isPaused = true;
        vibrator.cancel();
        if (visualizer != null) {
            // キャプチャエンジンを停止するだけ → release() は呼ばない
            visualizer.setEnabled(false);  // Visualizer must be disabled before release  [oai_citation:0‡Android Developers](https://developer.android.com/reference/android/media/audiofx/Visualizer?utm_source=chatgpt.com)
        }
    }

    // 再開メソッド
    public void resumeHaptics() {
        if (!isPaused || visualizer == null) return;
        isPaused = false;
        visualizer.setEnabled(true);     // 再度キャプチャをオンにする  [oai_citation:1‡マイクロソフトラーニング](https://learn.microsoft.com/en-us/dotnet/api/android.media.audiofx.visualizer.setenabled?view=net-android-34.0&utm_source=chatgpt.com)
    }

    /**
     * 時間領域（PCM）と周波数領域（FFT）の情報を解析し，
     * ハプティクスを生成する。
     */
    private void processFftPrimitives(short[] pcm, byte[] fft, int audioSessionId) {
        // 1) 時間領域：振幅ノーマライズ
        int n = pcm.length / 3;
        double bass   = rmsOf(pcm, 0,   n);
        double melody = rmsOf(pcm, n, 2*n);
        double other  = rmsOf(pcm, 2*n, pcm.length);
        double weighted = 2.0*bass + 1.0*melody + 0.5*other;
        double x = Math.min(1.0, weighted / 32768.0);
        final double t = 0.3, lowExp = 5.0, highExp = 6.0;
        double normAmp = (x < t)
                ? Math.pow(x/t, lowExp) * 2.0
                : 0.3 + Math.pow((x-t)/(1-t), highExp) * 4.0;
        int rmsAmp = (int)(normAmp * userScale * 255);

        // 2) 周波数領域：各帯域エネルギー比率
        int len = fft.length / 2;
        float[] mag = new float[len];
        for (int i = 0; i < len; i++) {
            int re = fft[2*i], im = fft[2*i+1];
            mag[i] = (float)Math.hypot(re, im);
        }
        int bassEnd = len * 200  / 22050;
        int midEnd  = len * 1500 / 22050;
        double bassSum=0, midSum=0, highSum=0;
        for (int i = 0; i < len; i++) {
            if      (i < bassEnd) bassSum += mag[i];
            else if (i < midEnd)  midSum  += mag[i];
            else                  highSum += mag[i];
        }
        float bassNorm = (float)(bassSum / (bassSum+midSum+highSum+1e-9));
        float midNorm  = (float)(midSum  / (bassSum+midSum+highSum+1e-9));
        float highNorm = (float)(highSum / (bassSum+midSum+highSum+1e-9));

        // 3) Composition：プリミティブを追加
        boolean hasPrimitive = false;
        VibrationEffect.Composition comp = VibrationEffect.startComposition();
        if (bassNorm > 0.15f) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD,      bassNorm,   0);
            hasPrimitive = true;
        }
        if (midNorm  > 0.15f) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN,      midNorm,   60);
            hasPrimitive = true;
        }
        if (highNorm > 0.15f) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK,      highNorm,  120);
            hasPrimitive = true;
        }

        // 4) 効果選択：プリミティブ有効かつサポートなら compose、そうでなければフォールバック
        VibrationEffect effect;
        if (hasPrimitive
                && vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_SPIN,
                VibrationEffect.Composition.PRIMITIVE_TICK)) {
            effect = comp.compose();
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK);
        }
        else {
            effect = VibrationEffect.createOneShot(FRAME_MS, rmsAmp);
        }

        // 5) CombinedVibration（API31+）または従来 vibrator.vibrate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager)
                    mContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vm.vibrate(CombinedVibration.createParallel(effect));
                return;
            }
        }
        vibrator.vibrate(effect);
    }

    /*==== 外部公開メソッド ============================================*/
    public void onPCM(short[] pcm) {
        if (!useHg) {
            // 古いデータを捨てつつキューに格納
            if (!pcmQueue.offer(pcm.clone())) {
                pcmQueue.poll();
                pcmQueue.offer(pcm.clone());
            }
        }
    }

    public void onFFT(byte[] fft) {
        short[] pcm = new short[fft.length];
        for (int i = 0; i < fft.length; i++) pcm[i] = (short) (Math.abs(fft[i]) & 0xFF);
        onPCM(pcm);
    }

    public void setUserScale(float scale) { userScale = scale; }

    public void release() {
        if (useHg && hg != null) hg.release();
        if (!useHg && handler != null) {
            handler.removeCallbacksAndMessages(null);
            thread.quitSafely();
        }
        vibrator.cancel();
    }

    /*==== ユーティリティ ==============================================*/
    private static double rmsOf(short[] buf, int from, int to) {
        long sumSq = 0;
        for (int i = from; i < to; i++) sumSq += (long) buf[i] * buf[i];
        return Math.sqrt(sumSq / (double)(to - from));
    }
}