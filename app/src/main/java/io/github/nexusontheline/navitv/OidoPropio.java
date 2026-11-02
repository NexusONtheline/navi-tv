package io.github.nexusontheline.navitv;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * NAVI graba tu voz ella misma (sin el reconocedor de Google): el control remoto
 * le pasa su micrófono a la app del asistente. Graba hasta que dejas de hablar y
 * entrega un WAV para que Gemini lo entienda.
 */
final class OidoPropio {
    private static final String TAG = "NAVI-Oido";
    static final int MUESTREO = 16000;
    private static final int TROZO_MS = 50;
    private static final int ESPERA_MAX_MS = 7000;     // si no empiezas a hablar en 7 s, nada
    private static final int TOTAL_MAX_MS = 15000;     // frase máxima
    private static final int SILENCIO_FIN_MS = 1200;   // tras hablar, 1,2 s de silencio = terminaste

    interface Oyente {
        void listo();
        void termino(byte[] wav);
        void nada();
        void error(String que);
    }

    private volatile boolean parar;
    private Thread hilo;

    void escuchar(Oyente o) {
        cancelar();
        parar = false;
        hilo = new Thread(() -> grabar(o), "navi-oido");
        hilo.start();
    }

    void cancelar() {
        parar = true;
        if (hilo != null) hilo.interrupt();
        hilo = null;
    }

    @SuppressWarnings("MissingPermission")
    private void grabar(Oyente o) {
        int minimo = AudioRecord.getMinBufferSize(MUESTREO, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MUESTREO,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimo, MUESTREO));
        } catch (Exception e) {
            o.error("no pude abrir el micrófono: " + e.getMessage());
            return;
        }
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            ar.release();
            o.error("micrófono no disponible");
            return;
        }
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] trozo = new short[MUESTREO * TROZO_MS / 1000];
        ByteBuffer bytes = ByteBuffer.allocate(trozo.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        long inicio = SystemClock.elapsedRealtime();
        double ruido = 0, maximo = 0;
        boolean voz = false;
        int silencioMs = 0, trozos = 0;
        try {
            ar.startRecording();
            o.listo();
            while (!parar) {
                int n = ar.read(trozo, 0, trozo.length);
                if (n <= 0) continue;
                double suma = 0;
                for (int i = 0; i < n; i++) suma += (double) trozo[i] * trozo[i];
                double rms = Math.sqrt(suma / n);
                maximo = Math.max(maximo, rms);
                bytes.clear();
                for (int i = 0; i < n; i++) bytes.putShort(trozo[i]);
                pcm.write(bytes.array(), 0, n * 2);
                trozos++;
                if (trozos % 10 == 0) Log.i(TAG, "nivel " + Math.round(rms) + " (ruido " + Math.round(ruido) + ")");

                long pasado = SystemClock.elapsedRealtime() - inicio;
                if (trozos <= 4) {  // los primeros 200 ms: medir el ruido de fondo
                    ruido = Math.max(ruido, rms);
                    continue;
                }
                double umbral = Math.max(ruido * 2.5, 300);
                if (rms > umbral) {
                    voz = true;
                    silencioMs = 0;
                } else if (voz) {
                    silencioMs += TROZO_MS;
                }
                if (voz && silencioMs >= SILENCIO_FIN_MS) break;
                if (!voz && pasado > ESPERA_MAX_MS) break;
                if (pasado > TOTAL_MAX_MS) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "grabando", e);
        } finally {
            try { ar.stop(); } catch (Exception ignored) {}
            ar.release();
        }
        Log.i(TAG, "fin: voz=" + voz + " máximo=" + Math.round(maximo) + " ruido=" + Math.round(ruido)
                + " duración=" + (SystemClock.elapsedRealtime() - inicio) + " ms");
        if (parar) return;
        if (!voz) o.nada();
        else o.termino(wav(pcm.toByteArray()));
    }

    private static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(MUESTREO).putInt(MUESTREO * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }
}
