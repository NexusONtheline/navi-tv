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
        return c;
    }

    public void guardar(Context ctx) {
        prefs(ctx).edit()
                .putString("etiquetas", etiquetas.trim())
                .putString("nunca", nuncaMostrar.trim())
                .putInt("segundos", Math.max(5, segundos))
                .putBoolean("horizontales", soloHorizontales)
                .putBoolean("reloj", mostrarReloj)
                .apply();
    }
}
