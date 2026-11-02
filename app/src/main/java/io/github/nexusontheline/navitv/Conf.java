package io.github.nexusontheline.navitv;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes del salvapantallas, guardados en la tele. */
public final class Conf {
    public static final String ETIQUETAS_POR_DEFECTO = "rating:general width:>=1600";

    public String etiquetas;
    public String nuncaMostrar;
    public int segundos;
    public boolean soloHorizontales;
    public boolean mostrarReloj;
    /** Widgets en el salvapantallas (clima, anime de hoy, NAVI durmiendo...). */
    public boolean widgets;

    // NAVI (asistente)
    public String geminiKey;
    public String modelo;
    public String modeloReserva;
    public String usuario;
    public String ciudad;
    public String voz;
    public float tono;
    /** NAVI aparece sola (saludo al prender y comentarios al azar). */
    public boolean proactiva;

    private Conf() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("salvapantallas", Context.MODE_PRIVATE);
    }

    public static Conf cargar(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Conf c = new Conf();
        c.etiquetas = p.getString("etiquetas", ETIQUETAS_POR_DEFECTO);
        c.nuncaMostrar = p.getString("nunca", "");
        c.segundos = Math.max(5, p.getInt("segundos", 60));
        c.soloHorizontales = p.getBoolean("horizontales", true);
        c.mostrarReloj = p.getBoolean("reloj", true);
        c.widgets = p.getBoolean("widgets", true);
        c.geminiKey = p.getString("gemini_key", "");
        c.modelo = p.getString("modelo", "gemini-3.5-flash-lite");
        c.modeloReserva = p.getString("modelo_reserva", "gemini-3.5-flash");
        c.usuario = p.getString("usuario", "");
        c.ciudad = p.getString("ciudad", "Guayaquil, Ecuador");
        c.voz = p.getString("voz", "");
        c.tono = p.getFloat("tono", 1.0f);
        c.proactiva = p.getBoolean("proactiva", true);
        return c;
    }

    public void guardar(Context ctx) {
        prefs(ctx).edit()
                .putString("etiquetas", etiquetas.trim())
                .putString("nunca", nuncaMostrar.trim())
                .putInt("segundos", Math.max(5, segundos))
                .putBoolean("horizontales", soloHorizontales)
                .putBoolean("reloj", mostrarReloj)
                .putBoolean("widgets", widgets)
                .putString("gemini_key", geminiKey.trim())
                .putString("modelo", modelo.trim())
                .putString("modelo_reserva", modeloReserva.trim())
                .putString("usuario", usuario.trim())
                .putString("ciudad", ciudad.trim())
                .putString("voz", voz.trim())
                .putFloat("tono", tono)
                .putBoolean("proactiva", proactiva)
                .apply();
    }
}
