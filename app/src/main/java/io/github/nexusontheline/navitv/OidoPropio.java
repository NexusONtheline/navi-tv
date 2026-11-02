package io.github.nexusontheline.navitv;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * NAVI graba tu voz ella misma. En estas teles el micrófono de verdad es el del control,
 * y el servicio del control solo lo "conecta" (un puente de audio) cuando ya hay una
 * grabación en marcha; mientras tanto, la tele entrega un micrófono falso con una señal
 * plana. Por eso:
 *  - se mide solo la parte que varía de la señal (la falsa da 0),
 *  - si solo llega la falsa, se abre otra grabación encima (como apretar el botón otra
 *    vez), y así hasta 3, para que el control conecte el puente,
 *  - «listo» (el bip) se avisa cuando ya llega audio de verdad.
 */
final class OidoPropio {
    private static final String TAG = "NAVI-Oido";
    static final int MUESTREO = 16000;
    private static final int TROZO_MS = 50;
    private static final int SIN_PUENTE_MS = 450;     // tras esto sin audio real, otra grabación
    private static final int MAX_GRABACIONES = 3;
    private static final int PUENTE_MAX_MS = 3500;    // si el control no conecta en 3,5 s, nada
    private static final int ESPERA_MAX_MS = 6000;    // tras el bip, si no hablas en 6 s, nada
    private static final int TOTAL_MAX_MS = 15000;
    private static final int SILENCIO_FIN_MS = 1100;

    interface Oyente {
        void listo();
        void termino(byte[] wav);
        void nada();
        void error(String que);
    }

    /** Cada grabación tiene su número: si empieza otra, la anterior se da cuenta y se calla. */
    private volatile int turno;
    private Thread hilo;

    synchronized void escuchar(Oyente o) {
        cancelar();
        final int mio = ++turno;
        hilo = new Thread(() -> grabar(o, mio), "navi-oido");
        hilo.start();
    }

    synchronized void cancelar() {
        turno++;
        if (hilo != null) hilo.interrupt();
        hilo = null;
    }

    @SuppressWarnings("MissingPermission")
    private static AudioRecord abrir() {
        int minimo = AudioRecord.getMinBufferSize(MUESTREO, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MUESTREO,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimo, MUESTREO));
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            ar.release();
            return null;
        }
        ar.startRecording();
        return ar;
    }

    /** Nivel de la parte que varía (sin la componente fija): la señal falsa da 0. */
    private static double nivel(short[] s, int n) {
        double media = 0;
        for (int i = 0; i < n; i++) media += s[i];
        media /= n;
        double suma = 0;
        for (int i = 0; i < n; i++) {
            double d = s[i] - media;
            suma += d * d;
        }
        return Math.sqrt(suma / n);
    }

    private void grabar(Oyente o, int mio) {
        List<AudioRecord> grabaciones = new ArrayList<>();
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] trozo = new short[MUESTREO * TROZO_MS / 1000];
        ByteBuffer bytes = ByteBuffer.allocate(trozo.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        long inicio = SystemClock.elapsedRealtime();
        long ultimaApertura = inicio, desdeReal = 0;
        boolean real = false, voz = false;
        double primerNivel = -1;  // el micro falso da SIEMPRE el mismo nivel (plano o un zumbido fijo)
        double ruido = 0, maximo = 0;
        int silencioMs = 0, trozosReales = 0;
        try {
            AudioRecord ar = abrir();
            if (ar == null) {
                o.error("micrófono no disponible");
                return;
            }
            grabaciones.add(ar);
            while (mio == turno) {
                AudioRecord actual = grabaciones.get(grabaciones.size() - 1);
                // vaciar las grabaciones viejas para que no se atasquen
                for (int i = 0; i < grabaciones.size() - 1; i++) grabaciones.get(i).read(trozo, 0, trozo.length,
                        AudioRecord.READ_NON_BLOCKING);
                int n = actual.read(trozo, 0, trozo.length);
                if (n <= 0) continue;
                long ahora = SystemClock.elapsedRealtime();
                double v = nivel(trozo, n);

                if (!real) {
                    if (primerNivel < 0) primerNivel = v;
                    boolean cambia = Math.abs(v - primerNivel) > Math.max(40, primerNivel * 0.04);
                    if (cambia) {  // ¡llegó audio de verdad! (el falso nunca cambia)
                        real = true;
                        desdeReal = ahora;
                        Log.i(TAG, "puente conectado tras " + (ahora - inicio) + " ms y "
                                + grabaciones.size() + " grabación(es)");
                        if (mio == turno) o.listo();
                    } else {
                        if (ahora - ultimaApertura > SIN_PUENTE_MS && grabaciones.size() < MAX_GRABACIONES) {
                            AudioRecord otra = abrir();
                            if (otra != null) grabaciones.add(otra);
                            ultimaApertura = ahora;
                            Log.i(TAG, "sin audio real: abro otra grabación (" + grabaciones.size() + ")");
                        }
                        if (ahora - inicio > PUENTE_MAX_MS) {
                            Log.i(TAG, "el control no conectó el micrófono");
                            break;
                        }
                        continue;
                    }
                }

                maximo = Math.max(maximo, v);
                bytes.clear();
                for (int i = 0; i < n; i++) bytes.putShort(trozo[i]);
                pcm.write(bytes.array(), 0, n * 2);
                trozosReales++;
                if (trozosReales % 10 == 0) Log.i(TAG, "nivel " + Math.round(v) + " (ruido " + Math.round(ruido) + ")");
                if (trozosReales <= 3) {  // ~150 ms para medir el fondo
                    ruido = Math.max(ruido, v);
                    continue;
                }
                double umbral = Math.max(ruido * 3, 700);
                if (v > umbral) {
                    voz = true;
                    silencioMs = 0;
                } else if (voz) {
                    silencioMs += TROZO_MS;
                }
                if (voz && silencioMs >= SILENCIO_FIN_MS) break;
                if (!voz && ahora - desdeReal > ESPERA_MAX_MS) break;
                if (ahora - inicio > TOTAL_MAX_MS) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "grabando", e);
        } finally {
            for (AudioRecord g : grabaciones) {
                try { g.stop(); } catch (Exception ignored) {}
                g.release();
            }
        }
        Log.i(TAG, "fin: real=" + real + " voz=" + voz + " máximo=" + Math.round(maximo)
                + " ruido=" + Math.round(ruido) + " duración=" + (SystemClock.elapsedRealtime() - inicio) + " ms");
        if (mio != turno) return;  // la cancelaron o empezó otra: no avisar
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
