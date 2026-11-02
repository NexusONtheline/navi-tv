package io.github.nexusontheline.navitv;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Elige la voz de NAVI entre las que tenga la tele (por defecto, una de mujer en español). */
public final class Voz {
    private static final String TAG = "NAVI-Voz";

    /** Voces de mujer de Google, de la que más nos gusta a la que menos. */
    private static final String[] PREFERIDAS = {
            "es-es-x-eea", "es-us-x-sfb", "es-us-x-esc", "es-es-x-eec", "es-us-x-esf",
    };

    private Voz() {}

    /** Llamar en onInit del TextToSpeech. */
    public static void configurar(TextToSpeech tts, Context ctx) {
        Conf conf = Conf.cargar(ctx);
        tts.setLanguage(new Locale("es", "ES"));
        tts.setPitch(conf.tono);
        tts.setSpeechRate(1.0f);

        List<Voice> espanol = new ArrayList<>();
        try {
            for (Voice v : tts.getVoices()) {
                if (v.getLocale().getLanguage().equals("es")) espanol.add(v);
            }
        } catch (Exception e) {
            Log.w(TAG, "No pude listar las voces", e);
            return;
        }
        List<String> nombres = new ArrayList<>();
        for (Voice v : espanol) nombres.add(v.getName() + (v.isNetworkConnectionRequired() ? "(red)" : ""));
        Collections.sort(nombres);
        Log.i(TAG, "Voces en español: " + nombres);

        List<String> orden = new ArrayList<>();
        if (!conf.voz.isEmpty()) orden.add(conf.voz);
        Collections.addAll(orden, PREFERIDAS);
        for (String buscada : orden) {
            Voice mejor = null;
            for (Voice v : espanol) {
                if (!v.getName().startsWith(buscada)) continue;
                // Mejor la que funciona sin internet
                if (mejor == null || (mejor.isNetworkConnectionRequired() && !v.isNetworkConnectionRequired())) mejor = v;
            }
            if (mejor != null) {
                tts.setVoice(mejor);
                Log.i(TAG, "Voz elegida: " + mejor.getName());
                return;
            }
        }
        Log.i(TAG, "Ninguna voz preferida instalada; uso la de por defecto");
    }
}
