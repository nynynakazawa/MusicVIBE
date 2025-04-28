package com.nakazawa.musicvibe;

import android.content.Context;
import android.media.AudioFormat;
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

/**
 * HapticEngine
 * ──────────────────────────────────────────────────────────────
 * ・アプリ内再生（audioSession > 0）
 *     ├─ HapticGenerator が使える端末→そのまま HG
 *     └─ 使えない端末           → Visualizer + Primitive 合成
 * ・BackGround 再生（audioSession == 0）
 *     └─ AudioRecord から PCM を受け取り，本 Runnable で滑らか振動
 */
@RequiresApi(api = Build.VERSION_CODES.S)
public class HapticEngine {

    /*==== 共通定数 =====================================================*/
    private static final String TAG = "HapticEngiine";
    private static final int    FRAME_MS      = 10;   // Visualizer 版用（従来どおり）
    private static final float  DEFAULT_SCALE = 0.7f; // 音量スケール初期値

    /*==== BackGround 専用定数 ==========================================*/
    private static final int    FRAME_MS_BG   = 30;   // 1 フレーム 30 ms
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

    /*==================================================================*/
    public HapticEngine(Context ctx, int audioSession) {
        boolean hgAvailable = HapticGenerator.isAvailable() && audioSession > 0;
        this.useHg = false;

        /*――― Vibrator ―――*/
        VibratorManager vm = ctx.getSystemService(VibratorManager.class);
        vibrator = vm.getDefaultVibrator();

        /*――― HapticGenerator 経路 ―――*/
        if (hgAvailable) {
            try {
                hg = HapticGenerator.create(audioSession);
                hg.setEnabled(true);
                useHg  = true;
                thread = null;
                handler = null;
                Log.d(TAG, "HapticGenerator enabled");
                return; // HG が動くなら残りは不要
            } catch (Exception e) {
                Log.w(TAG, "HapticGenerator init failed，fallbackへ", e);
            }
        }

        /*――― Fallback 経路 ―――*/
        if (audioSession == 0) {
            /* BackGround モード（AudioRecord → PCM キュー） */
            thread = new HandlerThread("HapticEngineBgThread");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.postDelayed(processRmsRunnableBg, FRAME_MS_BG);
        } else {
            /* アプリ内モード（Visualizer → Primitive 合成） */
            setupPrimitiveVisualizer(audioSession);
            thread  = null;
            handler = null;
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
    private void setupPrimitiveVisualizer(int sessionId) {
        try {
            Visualizer visualizer = new Visualizer(sessionId);
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            visualizer.setDataCaptureListener(
                    new Visualizer.OnDataCaptureListener() {
                        @Override public void onWaveFormDataCapture(Visualizer v, byte[] wf, int sr) { }
                        @Override public void onFftDataCapture(Visualizer v, byte[] fft, int sr) {
                            processFftPrimitives(fft);
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true
            );
            visualizer.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "Visualizer init error", e);
        }
    }

    private void processFftPrimitives(byte[] fft) {
        int len = fft.length / 2;
        float[] mag = new float[len];
        for (int i = 0; i < len; i++) {
            int re = fft[2 * i];
            int im = fft[2 * i + 1];
            mag[i] = (float) Math.hypot(re, im);
        }
        int bassEnd = len * 200  / 22050;
        int midEnd  = len * 1500 / 22050;
        double bassSum = 0, midSum = 0, highSum = 0;
        for (int i = 0; i < len; i++) {
            if (i < bassEnd)       bassSum += mag[i];
            else if (i < midEnd)   midSum  += mag[i];
            else                   highSum += mag[i];
        }
        double total = bassSum + midSum + highSum + 1e-9;
        float bassNorm = (float) (bassSum / total);
        float midNorm  = (float) (midSum  / total);
        float highNorm = (float) (highSum / total);

        boolean hasPrimitive = false;
        VibrationEffect.Composition comp = VibrationEffect.startComposition();
        if (bassNorm > 0.2f) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, bassNorm, 0);
            hasPrimitive = true;
        }
        if (midNorm > 0.2f) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, midNorm, 50);
            hasPrimitive = true;
        }
        if (highNorm > 0.2f) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, highNorm, 100);
            hasPrimitive = true;
        }
        if (hasPrimitive && vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_SPIN,
                VibrationEffect.Composition.PRIMITIVE_TICK)) {
            vibrator.vibrate(comp.compose());
        } else {
            float maxNorm = Math.max(bassNorm, Math.max(midNorm, highNorm));
            int amp = (int) (255 * maxNorm * userScale);
            if (amp > 0) {
                vibrator.vibrate(VibrationEffect.createOneShot(FRAME_MS, amp));
            }
        }
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