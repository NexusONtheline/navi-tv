package io.github.nexusontheline.navitv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

import java.util.Random;

/**
 * NAVI como asistente oficial de la tele: el sistema le pasa el botón del micro.
 * Además lleva su "reloj interno": saluda al prender la tele y, de vez en cuando,
 * mira la pantalla por si algo le llama la atención.
 */
public class NaviAsistente extends VoiceInteractionService {
    private static final String TAG = "NAVI-Asistente";
    /** Para probar desde la laptop: adb shell am broadcast -a ...PROBAR --es modo mirar|saludo */
    public static final String PROBAR = "io.github.nexusontheline.navitv.PROBAR";

    private static final long MIN = 60_000L;
    private static final long MIRAR_MIN = 7 * MIN, MIRAR_MAX = 18 * MIN;
    /** Entre una aparición y otra, al menos esto. */
    private static final long PAUSA_ENTRE_APARICIONES = 12 * MIN;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random azar = new Random();
    private final Runnable mirar = this::mirar;
    private final Runnable saludar = () -> aparecer(NaviSesion.SALUDO);
    private boolean listo;

    // ---- modo letra: lo que suena y su letra (la usa NaviSesion)
    static volatile Letras.Cancion cancionActual;
    private String claveSonando = "";
    private final java.util.concurrent.ExecutorService red = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable vigilarMusica = this::vigilarMusica;

    private final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                Log.i(TAG, "se prendió la pantalla");
                h.removeCallbacks(saludar);
                h.postDelayed(saludar, 8000);  // que cargue el inicio primero
                programarMirada();
            } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                h.removeCallbacks(saludar);
                h.removeCallbacks(mirar);
            } else if (PROBAR.equals(a)) {
                String modo = i.getStringExtra("modo");
                aparecer(NaviSesion.SALUDO.equals(modo) ? NaviSesion.SALUDO : NaviSesion.MIRAR_YA);
            }
        }
    };

    @Override
    public void onReady() {
        super.onReady();
        listo = true;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(PROBAR);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receptor, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receptor, f);

        // Recién encendida la tele: saludar
        if (SystemClock.elapsedRealtime() < 5 * MIN) h.postDelayed(saludar, 15000);
        programarMirada();
        h.postDelayed(vigilarMusica, 5000);
        Log.i(TAG, "lista");
    }

    /**
     * El servicio del control remoto (AtvRemote) arranca al asistente con un intent que trae
     * el canal de audio del micro. Por ahora lo anotamos entero para entender su formato.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            Log.i(TAG, "onStartCommand: acción=" + intent.getAction() + " datos=" + intent.getData()
                    + " componente=" + intent.getComponent());
            registrar("", intent.getExtras());
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private static void registrar(String prefijo, Bundle b) {
        if (b == null) return;
        try {
            for (String k : b.keySet()) {
                try {
                    Object v = b.get(k);
                    String extra = "";
                    if (v instanceof android.os.IBinder) {
                        try { extra = " descriptor=" + ((android.os.IBinder) v).getInterfaceDescriptor(); }
                        catch (Exception e) { extra = " descriptor=?" + e; }
                    }
                    Log.i(TAG, "  extra " + prefijo + k + " = "
                            + (v == null ? "null" : v.getClass().getName() + " " + v) + extra);
                    if (v instanceof Bundle) registrar(prefijo + k + ".", (Bundle) v);
                } catch (Throwable e) {
                    Log.i(TAG, "  extra " + prefijo + k + " ilegible: " + e);
                }
            }
        } catch (Throwable e) {
            Log.i(TAG, "  extras ilegibles: " + e);
        }
    }

    @Override
    public void onShutdown() {
        listo = false;
        h.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(receptor);
        } catch (IllegalArgumentException ignored) {
        }
        super.onShutdown();
    }

    /** Cada 3 s: ¿suena una canción? Si es nueva, buscar su letra; si hay letra, mostrarla. */
    private void vigilarMusica() {
        h.postDelayed(vigilarMusica, 3000);
        if (!listo || !Conf.cargar(this).letras) return;
        android.media.session.MediaController mc = Musica.sonando(this);
        if (mc == null) {
            claveSonando = "";
            return;
        }
        android.media.MediaMetadata md = mc.getMetadata();
        String titulo = Musica.texto(md, android.media.MediaMetadata.METADATA_KEY_TITLE,
                android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artista = Musica.texto(md, android.media.MediaMetadata.METADATA_KEY_ARTIST,
                android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        long duracion = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        String clave = titulo + "|" + artista;
        if (titulo.isEmpty()) return;
        if (!clave.equals(claveSonando)) {
            claveSonando = clave;
            cancionActual = null;
            final long detectada = android.os.SystemClock.elapsedRealtime();
            Log.i(TAG, "suena: " + titulo + " · " + artista);
            red.execute(() -> {
                Letras.Cancion c = Letras.buscar(titulo, artista, duracion);
                if (c != null) {
                    c.clave = clave;
                    c.detectada = detectada;
                    Letras.traducir(this, c);
                }
                h.post(() -> {
                    if (!clave.equals(claveSonando)) return;  // ya cambió de canción
                    cancionActual = c;
                    if (c != null) mostrarLetra();
                });
            });
        } else if (cancionActual != null && !NaviSesion.visible && !clave.equals(NaviSesion.letraQuitada)) {
            mostrarLetra();  // p. ej. después de hablarle con el micro, vuelve la letra
        }
    }

    private void mostrarLetra() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isInteractive() || Salvapantallas.activo || NaviSesion.visible) return;
        Bundle args = new Bundle();
        args.putString(NaviSesion.MODO, NaviSesion.LETRA);
        try {
            showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST);
        } catch (Exception e) {
            Log.w(TAG, "no pude mostrar la letra", e);
        }
    }

    private void programarMirada() {
        h.removeCallbacks(mirar);
        long espera = MIRAR_MIN + (long) (azar.nextDouble() * (MIRAR_MAX - MIRAR_MIN));
        h.postDelayed(mirar, espera);
        Log.i(TAG, "próxima mirada en " + espera / MIN + " min");
    }

    private void mirar() {
        programarMirada();
        long desde = SystemClock.elapsedRealtime() - NaviSesion.ultimaAparicion;
        if (NaviSesion.ultimaAparicion > 0 && desde < PAUSA_ENTRE_APARICIONES) {
            Log.i(TAG, "apareció hace poco; no miro");
            return;
        }
        aparecer(NaviSesion.MIRAR);
    }

    private void aparecer(String modo) {
        if (!listo) return;
        boolean probando = NaviSesion.MIRAR_YA.equals(modo);
        if (!probando && !Conf.cargar(this).proactiva) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isInteractive() || Salvapantallas.activo || NaviSesion.visible) {
            Log.i(TAG, "ahora no (pantalla apagada, salvapantallas o ya visible)");
            return;
        }
        Bundle args = new Bundle();
        args.putString(NaviSesion.MODO, modo);
        try {
            showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
        } catch (Exception e) {
            Log.w(TAG, "no pude aparecer", e);
        }
    }
}
