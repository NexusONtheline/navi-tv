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
    private static final long MIRAR_MIN = 10 * MIN, MIRAR_MAX = 25 * MIN;
    /** Entre una aparición y otra, al menos esto. */
    private static final long PAUSA_ENTRE_APARICIONES = 20 * MIN;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random azar = new Random();
    private final Runnable mirar = this::mirar;
    private final Runnable saludar = () -> aparecer(NaviSesion.SALUDO);
    private boolean listo;

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
        Log.i(TAG, "lista");
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
