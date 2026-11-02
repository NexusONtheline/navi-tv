package io.github.nexusontheline.navitv;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Lo que NAVI puede hacer en la tele. Cada herramienta devuelve un texto para la IA. */
public final class Herramientas {
    private static final String TIZENTUBE = "io.gh.reisxd.tizentube.cobalt";

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public Herramientas(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ------------------------------------------------------------ para Gemini

    public JSONArray declaraciones() throws JSONException {
        JSONArray d = new JSONArray();
        d.put(funcion("abrir_app",
                "Abre una app instalada en la tele (Netflix, YouTube, RetroArch...). Para 'abre YouTube' usa esta, no buscar_en_youtube.",
                props("nombre", "string", "Nombre de la app, como lo diría el usuario"),
                "nombre"));
        d.put(funcion("buscar_en_youtube",
                "Busca videos concretos en YouTube (con TizenTube, sin anuncios). Solo si pide buscar o poner algo específico.",
                props("busqueda", "string", "Qué buscar"),
                "busqueda"));
        JSONObject vol = props("accion", "string",
                "subir, bajar, silenciar, activar_sonido o fijar");
        vol.getJSONObject("properties").put("nivel",
                new JSONObject().put("type", "integer").put("description", "0 a 100, solo para fijar"));
        d.put(funcion("volumen", "Cambia el volumen de la tele.", vol, "accion"));
        d.put(funcion("clima",
                "Da el tiempo actual y el pronóstico de hoy.",
                props("ciudad", "string", "Ciudad; vacío = la del usuario"), null));
        d.put(funcion("abrir_ajustes",
                "Abre una pantalla de ajustes de la tele para que el usuario la vea.",
                props("seccion", "string",
                        "general, wifi, bluetooth, pantalla, sonido, apps, fecha, accesibilidad o salvapantallas"),
                "seccion"));
        JSONObject sal = props("etiquetas", "string",
                "Etiquetas de Safebooru separadas por espacio (ej. 'scenery rating:general')");
        JSONObject p = sal.getJSONObject("properties");
        p.put("nunca_mostrar", new JSONObject().put("type", "string")
                .put("description", "Etiquetas que nunca deben salir, separadas por espacio"));
        p.put("segundos", new JSONObject().put("type", "integer").put("description", "Segundos por imagen"));
        p.put("ver_ahora", new JSONObject().put("type", "boolean").put("description", "Mostrarlo ya"));
        JSONObject letrasParams = props("accion", "string", "quitar, poner, desactivar, activar, atrasada o adelantada");
        letrasParams.getJSONObject("properties").put("segundos",
                new JSONObject().put("type", "number").put("description", "Para atrasada/adelantada (por defecto 2)"));
        d.put(funcion("letras",
                "Modo letra (karaoke abajo cuando suena una canción). quitar = esconder la letra de la "
                        + "canción actual; poner = volver a mostrarla; desactivar = no mostrar letras nunca; activar = sí; "
                        + "atrasada = la letra va detrás de la música (la adelanto); adelantada = va antes (la atraso). "
                        + "Para atrasada/adelantada puedes dar segundos (por defecto 2).",
                letrasParams, "accion"));
        d.put(funcion("aparecer_sola",
                "Activa o desactiva que NAVI aparezca sola en la pantalla a comentar lo que ve y a saludar al prender la tele.",
                new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("activar", new JSONObject().put("type", "boolean"))), "activar"));
        d.put(funcion("recordar",
                "Guarda algo que tu persona te contó y vale la pena recordar para siempre (gustos, nombres, planes, cosas importantes).",
                props("dato", "string", "Lo que hay que recordar, en una frase corta"), "dato"));
        d.put(funcion("olvidar",
                "Borra un recuerdo que ya no es cierto o que te pidió olvidar.",
                props("dato", "string", "El recuerdo a borrar (o parte de él)"), "dato"));
        d.put(funcion("salvapantallas",
                "Cambia el salvapantallas de NAVI (imágenes de Safebooru). Solo cambia lo que se indique.",
                sal, null));
        return d;
    }

    public String ejecutar(String nombre, JSONObject a) {
        try {
            switch (nombre) {
                case "abrir_app": return abrirApp(a.optString("nombre"));
                case "buscar_en_youtube": return buscarEnYoutube(a.optString("busqueda"));
                case "volumen": return volumen(a.optString("accion"), a.optInt("nivel", -1));
                case "clima": return clima(a.optString("ciudad"));
                case "abrir_ajustes": return abrirAjustes(a.optString("seccion"));
                case "salvapantallas": return salvapantallas(a);
                case "letras": {
                    Conf c = Conf.cargar(ctx);
                    Letras.Cancion actual = NaviAsistente.cancionActual;
                    double seg = a.optDouble("segundos", 2);
                    switch (a.optString("accion")) {
                        case "atrasada":
                        case "adelantada": {
                            if (actual == null) return "Ahora no hay ninguna letra en pantalla.";
                            long ms = Math.round(seg * 1000) * ("atrasada".equals(a.optString("accion")) ? 1 : -1);
                            actual.ajusteMs += ms;
                            return "Listo, moví la letra " + Math.abs(seg) + " segundos "
                                    + (ms > 0 ? "hacia adelante." : "hacia atrás.");
                        }
                        case "quitar":
                            NaviSesion.letraQuitada = actual != null ? actual.clave : "";
                            return actual != null ? "Listo, ya no muestro la letra de esta canción."
                                    : "Ahora no hay ninguna letra en pantalla.";
                        case "poner":
                            NaviSesion.letraQuitada = "";
                            c.letras = true;
                            c.guardar(ctx);
                            return actual != null ? "Listo, la letra vuelve en un momento."
                                    : "No encontré la letra de lo que suena (o no es una canción).";
                        case "desactivar":
                            c.letras = false;
                            c.guardar(ctx);
                            return "Listo, ya no mostraré letras.";
                        default:
                            c.letras = true;
                            c.guardar(ctx);
                            return "Listo, mostraré la letra cuando suene una canción.";
                    }
                }
                case "aparecer_sola": {
                    Conf c = Conf.cargar(ctx);
                    c.proactiva = a.optBoolean("activar", true);
                    c.guardar(ctx);
                    return c.proactiva ? "Listo: volveré a aparecer sola de vez en cuando."
                            : "Listo: ya no apareceré sola; solo cuando me llames con el micro.";
                }
                case "recordar": return Memoria.recordar(ctx, a.optString("dato"));
                case "olvidar": return Memoria.olvidar(ctx, a.optString("dato"));
                default: return "Esa herramienta no existe.";
            }
        } catch (Exception e) {
            return "Falló: " + e.getMessage();
        }
    }

    /** Nombres de las apps instaladas, para que NAVI sepa qué hay en la tele. */
    public String listaApps() {
        return String.join(", ", apps().keySet());
    }

    // ------------------------------------------------------------ herramientas

    private String abrirApp(String nombre) {
        String buscado = normalizar(nombre);
        // «Abre YouTube» = TizenTube, el YouTube sin anuncios
        if (buscado.contains("youtube") && !buscado.contains("music") && instalada(TIZENTUBE)) buscado = "tizentube";
        Map<String, Intent> apps = apps();
        Intent elegido = null;
        String etiqueta = null;
        for (Map.Entry<String, Intent> e : apps.entrySet()) {
            String n = normalizar(e.getKey());
            if (n.equals(buscado)) { elegido = e.getValue(); etiqueta = e.getKey(); break; }
            if (elegido == null && (n.contains(buscado) || buscado.contains(n))) {
                elegido = e.getValue();
                etiqueta = e.getKey();
            }
        }
        if (elegido == null) return "No encontré «" + nombre + "». Apps instaladas: " + listaApps();
        lanzar(elegido);
        return "Abrí " + etiqueta + ".";
    }

    private String buscarEnYoutube(String busqueda) throws IOException {
        Uri uri = Uri.parse("https://www.youtube.com/results?search_query="
                + URLEncoder.encode(busqueda, "UTF-8"));
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        if (instalada(TIZENTUBE)) i.setPackage(TIZENTUBE);
        lanzar(i);
        return "Busqué «" + busqueda + "» en YouTube.";
    }

    private String volumen(String accion, int nivel) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int s = AudioManager.STREAM_MUSIC;
        int max = am.getStreamMaxVolume(s);
        int f = AudioManager.FLAG_SHOW_UI;
        switch (accion) {
            case "subir": am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f);
                am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f); break;
            case "bajar": am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, f);
                am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, f); break;
            case "silenciar":
                if (Build.VERSION.SDK_INT >= 23) am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, f);
                return "Silencié la tele.";
            case "activar_sonido":
                if (Build.VERSION.SDK_INT >= 23) am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, f);
                break;
            case "fijar":
                if (nivel < 0) return "Falta el nivel (0 a 100).";
                am.setStreamVolume(s, Math.round(Math.min(100, nivel) * max / 100f), f);
                break;
            default: return "No entendí qué hacer con el volumen.";
        }
        return "Volumen: " + Math.round(am.getStreamVolume(s) * 100f / max) + " %.";
    }

    private String clima(String ciudad) throws Exception {
        if (ciudad == null || ciudad.trim().isEmpty()) ciudad = Conf.cargar(ctx).ciudad;
        if (ciudad == null || ciudad.trim().isEmpty()) return "No sé en qué ciudad vives: pregúntaselo.";
        ciudad = ciudad.replace("'", "").replace("\"", "").trim();
        String nombre = ciudad.split(",")[0].trim();
        JSONObject geo = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=5&language=es&name="
                + URLEncoder.encode(nombre, "UTF-8")));
        JSONArray res = geo.optJSONArray("results");
        if (res == null || res.length() == 0) return "No encontré la ciudad «" + ciudad + "».";
        JSONObject lugar = res.getJSONObject(0);
        String pista = normalizar(ciudad);
        for (int i = 0; i < res.length(); i++) {
            String pais = normalizar(res.getJSONObject(i).optString("country"));
            if (!pais.isEmpty() && pista.contains(pais)) { lugar = res.getJSONObject(i); break; }
        }
        JSONObject t = new JSONObject(get(String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f&timezone=auto&forecast_days=1"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code"
                        + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                lugar.getDouble("latitude"), lugar.getDouble("longitude"))));
        JSONObject c = t.getJSONObject("current");
        JSONObject d = t.getJSONObject("daily");
        return String.format(Locale.forLanguageTag("es"),
                "%s: %s, %.0f °C (sensación %.0f °C), humedad %d %%. Hoy entre %.0f y %.0f °C, %d %% de probabilidad de lluvia.",
                lugar.optString("name"), descripcion(c.optInt("weather_code")),
                c.optDouble("temperature_2m"), c.optDouble("apparent_temperature"),
                c.optInt("relative_humidity_2m"),
                d.getJSONArray("temperature_2m_min").optDouble(0),
                d.getJSONArray("temperature_2m_max").optDouble(0),
                d.getJSONArray("precipitation_probability_max").optInt(0));
    }

    private String abrirAjustes(String seccion) {
        String accion;
        switch (seccion) {
            case "wifi": accion = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": accion = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "pantalla": accion = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sonido": accion = Settings.ACTION_SOUND_SETTINGS; break;
            case "apps": accion = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "fecha": accion = Settings.ACTION_DATE_SETTINGS; break;
            case "accesibilidad": accion = Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
            case "salvapantallas":
                lanzar(new Intent(ctx, Ajustes.class));
                return "Abrí los ajustes del salvapantallas de NAVI.";
            default: accion = Settings.ACTION_SETTINGS;
        }
        try {
            lanzarODar(new Intent(accion));
            return "Abrí los ajustes de " + seccion + ".";
        } catch (ActivityNotFoundException e) {
            lanzar(new Intent(Settings.ACTION_SETTINGS));
            return "Esa sección no se puede abrir directo; abrí los ajustes generales.";
        }
    }

    private String salvapantallas(JSONObject a) {
        Conf c = Conf.cargar(ctx);
        if (a.has("etiquetas")) c.etiquetas = a.optString("etiquetas");
        if (a.has("nunca_mostrar")) c.nuncaMostrar = a.optString("nunca_mostrar");
        if (a.has("segundos")) c.segundos = a.optInt("segundos", c.segundos);
        c.guardar(ctx);
        if (a.optBoolean("ver_ahora")) lanzar(new Intent(ctx, VistaPrevia.class));
        Conf n = Conf.cargar(ctx);
        return "Salvapantallas: etiquetas «" + n.etiquetas + "», nunca «" + n.nuncaMostrar
                + "», " + n.segundos + " s por imagen.";
    }

    // ------------------------------------------------------------ ayudas

    private Map<String, Intent> apps() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Intent> apps = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                String paquete = r.activityInfo.packageName;
                if (paquete.equals(ctx.getPackageName())) continue;
                String etiqueta = r.loadLabel(pm).toString().trim();
                if (apps.containsKey(etiqueta)) continue;
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(paquete, r.activityInfo.name);
                apps.put(etiqueta, i);
            }
        }
        return apps;
    }

    private boolean instalada(String paquete) {
        try {
            ctx.getPackageManager().getPackageInfo(paquete, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void lanzar(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ui.post(() -> {
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
        });
    }

    /** Como lanzar, pero avisa si no hay quien abra el intent. */
    private void lanzarODar(Intent i) {
        if (i.resolveActivity(ctx.getPackageManager()) == null) throw new ActivityNotFoundException();
        lanzar(i);
    }

    static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]", "");
    }

    private static String descripcion(int codigo) {
        if (codigo == 0) return "despejado";
        if (codigo <= 2) return "parcialmente nublado";
        if (codigo == 3) return "nublado";
        if (codigo <= 48) return "con niebla";
        if (codigo <= 57) return "llovizna";
        if (codigo <= 67) return "lluvia";
        if (codigo <= 77) return "nieve";
        if (codigo <= 82) return "chubascos";
        return "tormenta";
    }

    private static JSONObject funcion(String nombre, String desc, JSONObject params, String requerido)
            throws JSONException {
        if (requerido != null) params.put("required", new JSONArray().put(requerido));
        return new JSONObject().put("name", nombre).put("description", desc).put("parameters", params);
    }

    private static JSONObject props(String nombre, String tipo, String desc) throws JSONException {
        return new JSONObject().put("type", "object").put("properties",
                new JSONObject().put(nombre, new JSONObject().put("type", tipo).put("description", desc)));
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "NAVI-TV");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
