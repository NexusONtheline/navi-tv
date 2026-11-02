package io.github.nexusontheline.navitv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Letras sincronizadas de LRCLIB (gratis y abierto) para lo que suena en la tele.
 * Los títulos de YouTube traen basura («[Official Music Video]», «(Lyrics)»...): se limpian.
 */
final class Letras {
    private static final String TAG = "NAVI-Letras";
    private static final Pattern LINEA = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)");

    /** Una canción con su letra: tiempos en ms y líneas (y su traducción si hace falta). */
    static final class Cancion {
        String clave, titulo, artista;
        /** Cuándo empezó a sonar (SystemClock.elapsedRealtime), para el reloj propio. */
        long detectada;
        final List<Long> tiempos = new ArrayList<>();
        final List<String> lineas = new ArrayList<>();
        List<String> traduccion;  // null = no hace falta (ya está en español)

        /** Índice de la línea que suena en la posición dada (o -1 antes de empezar). */
        int indice(long posicionMs) {
            int i = -1;
            for (int k = 0; k < tiempos.size(); k++) {
                if (tiempos.get(k) <= posicionMs) i = k;
                else break;
            }
            return i;
        }
    }

    private Letras() {}

    /** Quita lo que YouTube le pega a los títulos de canciones. */
    static String limpiar(String t) {
        String s = t.replaceAll("(?i)[\\[(【][^\\])】]*(official|oficial|video|lyric|letra|audio|mv|m/v|hd|4k|live|en vivo|visualizer|remaster)[^\\])】]*[\\])】]", " ");
        s = s.replaceAll("(?i)\\b(official|oficial)\\s+(music\\s+)?(video|audio|lyric video)\\b", " ");
        s = s.replaceAll("(?i)\\s*[|•].*$", " ");  // «Canción | Canal»
        s = s.replaceAll("(?i)\\s(ft\\.?|feat\\.?)\\s.*$", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Busca la letra sincronizada. Devuelve null si no hay (o no es una canción).
     * @param duracionMs lo que dura el video según la tele (para elegir la versión), 0 si no se sabe
     */
    static Cancion buscar(String titulo, String artista, long duracionMs) {
        try {
            String limpio = limpiar(titulo);
            String art = artista == null ? "" : artista.replace(" - Topic", "").replace("VEVO", "").trim();
            String cancion = limpio;
            int guion = limpio.indexOf(" - ");
            if (guion > 0) {  // «Artista - Canción»
                if (art.isEmpty()) art = limpio.substring(0, guion).trim();
                cancion = limpio.substring(guion + 3).trim();
            }
            String q = (art.isEmpty() || cancion.toLowerCase().contains(art.toLowerCase()) ? "" : art + " ") + cancion;
            JSONArray r = consultar("https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8"));
            if (r.length() == 0 && !art.isEmpty()) {  // plan B: artista y canción por separado
                r = consultar("https://lrclib.net/api/search?track_name=" + URLEncoder.encode(cancion, "UTF-8")
                        + "&artist_name=" + URLEncoder.encode(art, "UTF-8"));
            }
            if (r.length() == 0) {  // plan C: solo el nombre de la canción
                r = consultar("https://lrclib.net/api/search?track_name=" + URLEncoder.encode(cancion, "UTF-8"));
            }
            JSONObject mejor = null;
            double mejorDif = Double.MAX_VALUE;
            for (int i = 0; i < r.length(); i++) {
                JSONObject x = r.getJSONObject(i);
                String lrc = x.optString("syncedLyrics", "");
                if (lrc.isEmpty() || lrc.equals("null") || x.optBoolean("instrumental")) continue;
                double dif = duracionMs > 0 ? Math.abs(x.optDouble("duration", 0) * 1000 - duracionMs) : i;
                if (dif < mejorDif) {
                    mejorDif = dif;
                    mejor = x;
                }
            }
            if (mejor == null) {
                Log.i(TAG, "sin letra sincronizada para «" + q + "»");
                return null;
            }
            // Si la duración no se parece nada, seguramente no es esa canción
            if (duracionMs > 0 && mejorDif > 45_000) {
                Log.i(TAG, "la letra más parecida dura muy distinto (" + Math.round(mejorDif / 1000) + " s): no la uso");
                return null;
            }
            Cancion c = new Cancion();
            c.titulo = mejor.optString("trackName");
            c.artista = mejor.optString("artistName");
            for (String l : mejor.getString("syncedLyrics").split("\n")) {
                Matcher m = LINEA.matcher(l.trim());
                if (!m.matches()) continue;
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000;
                if (m.group(3) != null) {
                    String frac = (m.group(3) + "00").substring(0, 3);
                    ms += Long.parseLong(frac);
                }
                c.tiempos.add(ms);
                c.lineas.add(m.group(4).trim());
            }
            Log.i(TAG, "letra: " + c.titulo + " · " + c.artista + " (" + c.lineas.size() + " líneas, dif "
                    + Math.round(mejorDif / 1000) + " s)");
            return c.lineas.isEmpty() ? null : c;
        } catch (Exception e) {
            Log.w(TAG, "buscar", e);
            return null;
        }
    }

    /** Una búsqueda en LRCLIB; si falla (a veces da error), lista vacía. */
    private static JSONArray consultar(String url) {
        try {
            return new JSONArray(Herramientas.get(url));
        } catch (Exception e) {
            Log.w(TAG, "LRCLIB falló: " + url + " (" + e.getClass().getSimpleName() + ")");
            return new JSONArray();
        }
    }

    /** Si la letra no está en español, pide la traducción línea a línea a Gemini. */
    static void traducir(Context ctx, Cancion c) {
        try {
            List<String> t = Cerebro.de(ctx).traducirLetra(c.lineas);
            if (t != null && t.size() == c.lineas.size()) c.traduccion = t;
        } catch (Exception e) {
            Log.w(TAG, "traducir", e);
        }
    }
}
