package com.nakazawa.musicvibe;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.media.audiofx.HapticGenerator;
import androidx.annotation.RequiresApi;
import android.util.Log;
import java.util.concurrent.ArrayBlockingQueue;
import android.media.audiofx.Visualizer;

/**
 * HapticEngine:
 * - Android 12 以降で HapticGenerator が利用可能ならそれを使用（リアルタイム音声連動）
 * - そうでない場合は PCM を受け取って RMS を計算し、Vibrator.createOneShot() でワンショット振動
 */
@RequiresApi(api = Build.VERSION_CODES.S)
public class HapticEngine {
    private static final String TAG = "HapticEngine";
    private static final int FRAME_MS = 10;

    private final Vibrator vibrator;
    private final boolean useHg;
    private HapticGenerator hg;
    private final ArrayBlockingQueue<short[]> pcmQueue = new ArrayBlockingQueue<>(64);
    private final HandlerThread thread;
    private final Handler handler;
    private float userScale = 2.5f;

    // フォールバック用 RMS→ワンショット振動 Runnable
    /**
     * PCM フレームを RMS→振動量に変換（フォールバック方式）
     * - 低域と主旋律帯域を強調し、それ以外を抑制
     * - 既存コードとの差分は ★ コメント部のみ
     */
    private final Runnable processRmsRunnable = new Runnable() {
        @Override public void run() {
            short[] pcm = pcmQueue.poll();
            if (pcm != null) {

                /*───────────────────
                 * ① FFT でバンド別の粗い音量を得る
                 *──────────────────*/
                double bass   = 0.0;  // 0–200 Hz
                double melody = 0.0;  // 200–1 500 Hz
                double other  = 0.0;  // 1 500 Hz 以上

            /* ★ FFT の代わりに「擬似バンド RMS」を求める -------------
               Visualizer から渡される byte[] fft を直接処理するのが理想だが、
               ここは既存 short[] pcm だけで完結させるため、
               フレームを 3 分割して「低域・中域・高域」の近似 RMS を得る。
               （20 ms / 44.1 kHz → 882 サンプルなので粗くても聴感上は十分）
            ---------------------------------------------------------------- */
                int n = pcm.length / 3;
                bass   = rmsOf(pcm, 0, n);             // 下 1/3 ≈ 低域寄り
                melody = rmsOf(pcm, n, 2 * n);         // 中央 1/3 ≈ メロディ帯域
                other  = rmsOf(pcm, 2 * n, pcm.length);// 上 1/3 ≈ 高域

                /*───────────────────
                 * ② バンド重み付け & 正規化
                 *──────────────────*/
                double weighted = 1.5 * bass
                        + 2.5 * melody
                        + 0.25 * other;

                // エネルギーを 0–1 に収める簡易ノーマライズ
                double x = Math.min(1.0, weighted / 32768.0);

                /*───────────────────
                 * ③ 2 段階べき乗でコントラスト強調
                 *──────────────────*/
                double t = 0.25;          // 分岐点
                double lowExp  = 4.0;     // 低レベル音の抑制は緩め
                double highExp = 8.0;     // 高レベル音の立ち上がりを鋭く
                double norm = (x < t)
                        ? Math.pow(x / t, lowExp) * 0.3            // 小さい音は 0.3 に圧縮
                        : 0.4 + Math.pow((x - t) / (1 - t), highExp) * 0.6; // 大きい音は 1- 0.3

                /*───────────────────
                 * ④ 振動振幅にスケール
                 *──────────────────*/
                int amp = (int) (norm * userScale * 255);
                if (amp > 7) {                                  // しきい値以下は無振動
                    vibrator.vibrate(VibrationEffect.createOneShot(FRAME_MS, amp));
                }
            }
            handler.postDelayed(this, FRAME_MS);
        }

        /** 与えられた short 配列の部分区間の RMS を返す（擬似バンド用） */
        private double rmsOf(short[] buf, int from, int to) {
            long sumSq = 0;
            for (int i = from; i < to; i++) sumSq += (long) buf[i] * buf[i];
            return Math.sqrt(sumSq / (double) (to - from));
        }
    };

    /**
     * @param ctx          コンテキスト
     * @param audioSession プレイヤーの audioSessionId を渡す
     */
    public HapticEngine(Context ctx, int audioSession) {
        Log.d(TAG, "ctor: audioSession=" + audioSession
                + "  isAvailable=" + HapticGenerator.isAvailable());
        VibratorManager vm = ctx.getSystemService(VibratorManager.class);
        vibrator = vm.getDefaultVibrator();

        if (HapticGenerator.isAvailable()) {
            // 既存コード（HapticGenerator 利用）
            hg = HapticGenerator.create(audioSession);
            hg.setEnabled(true);
            thread = null;
            handler = null;
            useHg = true;
        } else {
            // フォールバック：Visualizer を使ったリアルタイム FFT → プリミティブ振動
            thread = null;
            handler = null;
            useHg = false;
            setupPrimitiveVisualizer(audioSession);
        }
    }

    /**
     * PCM データ(short[]) を逐次渡す
     */
    public void onPCM(short[] pcm) {
        Log.d(TAG, "onPCM: useHg=" + useHg + "  pcm.length=" + pcm.length);
        if (!useHg) {
            Log.d(TAG, "onPCM: queueing for fallback vibration");
            boolean offered = pcmQueue.offer(pcm.clone());
            Log.d(TAG, "onPCM: queued=" + offered + ", queueSize=" + pcmQueue.size());
            if (!offered) {
                pcmQueue.poll();
                pcmQueue.offer(pcm.clone());
                Log.d(TAG, "onPCM: queue overflow, polled one, newSize=" + pcmQueue.size());
            }
        } else {
            Log.d(TAG, "onPCM: skipping because useHg==true");
        }
    }

    /**
     * FFT コールバック（Visualizer から呼ばれる）
     */
    public void onFFT(byte[] fft) {
        Log.d(TAG, "onFFT: called, fft.length=" + (fft != null ? fft.length : "null"));
        short[] pcm = new short[fft.length];
        for (int i = 0; i < fft.length; i++) {
            pcm[i] = (short) (Math.abs(fft[i]) & 0xFF);
        }
        onPCM(pcm);
    }

    /**
     * 音量キーでの振動スケール更新
     */
    public void setUserScale(float scale) {
        Log.d(TAG, "setUserScale: scale=" + scale);
        userScale = scale;
    }

    /**
     * ② Visualizer を設定し、FFT キャプチャを受けてプリミティブ効果を生成する
     */
    private void setupPrimitiveVisualizer(int sessionId) {
        try {
            Visualizer visualizer = new Visualizer(sessionId);
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);                                         // 最大サイズで高解像度FFT取得  [oai_citation:0‡Android Developers](https://developer.android.com/reference/android/media/audiofx/Visualizer?utm_source=chatgpt.com)
            visualizer.setDataCaptureListener(
                    new Visualizer.OnDataCaptureListener() {
                        @Override public void onWaveFormDataCapture(Visualizer v, byte[] wf, int sr) { /* nop */ }
                        @Override public void onFftDataCapture(Visualizer v, byte[] fft, int sr) {
                            processFftPrimitives(fft);                                                               // FFT 取得ごとに振動処理
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,    // 約20ms 毎の更新頻度  [oai_citation:1‡Android GoogeSource](https://android.googlesource.com/platform/frameworks/base/%2B/fa5ecdc/media/java/android/media/audiofx/Visualizer.java?utm_source=chatgpt.com)
                    false,
                    true
            );
            visualizer.setEnabled(true);                                                                         // キャプチャ開始
        } catch (Exception e) {
            Log.e(TAG, "Primitive Visualizer init error", e);
        }
    }

    /**
     * ③ FFT バイト列から周波数帯別エネルギーを算出し、プリミティブを合成・再生する
     */
    private void processFftPrimitives(byte[] fft) {
        // FFT バイト列から振幅スペクトルを計算
        int len = fft.length / 2;
        float[] mag = new float[len];
        for (int i = 0; i < len; i++) {
            int re = fft[2 * i];
            int im = fft[2 * i + 1];
            mag[i] = (float) Math.hypot(re, im);
        }

        // 周波数帯域を三分割（0–200Hz, 200–1.5kHz, 1.5kHz–ナイキスト）
        int bassEnd = len * 200  / 22050;
        int midEnd  = len * 1500 / 22050;

        double bassSum = 0, midSum = 0, highSum = 0;
        for (int i = 0; i < len; i++) {
            if (i < bassEnd)       bassSum += mag[i];
            else if (i < midEnd)   midSum  += mag[i];
            else                   highSum += mag[i];
        }

        // 0–1 正規化
        double total = bassSum + midSum + highSum + 1e-9;
        float bassNorm = (float) (bassSum / total);
        float midNorm  = (float) (midSum  / total);
        float highNorm = (float) (highSum / total);

        // プリミティブ効果を組み立て
        VibrationEffect.Composition comp = VibrationEffect.startComposition();
        if (bassNorm > 0.2f) {
            comp.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    bassNorm,
                    0
            );
        }
        if (midNorm > 0.2f) {
            comp.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_SPIN,
                    midNorm,
                    50
            );
        }
        if (highNorm > 0.2f) {
            comp.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    highNorm,
                    100
            );
        }

        // Composition から VibrationEffect を生成
        VibrationEffect effect = comp.compose();

        // 互換性チェック：areAllPrimitivesSupported は boolean を返す
        if (vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_SPIN,
                VibrationEffect.Composition.PRIMITIVE_TICK
        )) {
            vibrator.vibrate(effect);
        } else {
            float maxNorm = Math.max(bassNorm, Math.max(midNorm, highNorm));
            int amp = (int) (255 * maxNorm * userScale);
            vibrator.vibrate(
                    VibrationEffect.createOneShot(FRAME_MS, amp)
            );
        }
    }


    /**
     * リソース解放
     */
    public void release() {
        Log.d(TAG, "release: cleaning up");
        if (useHg && hg != null) {
            hg.release();
            Log.d(TAG, "release: HapticGenerator released");
        }
        if (!useHg && handler != null) {
            handler.removeCallbacks(processRmsRunnable);
            thread.quitSafely();
        }
        vibrator.cancel();
        Log.d(TAG, "release: vibrator cancelled");
    }
}