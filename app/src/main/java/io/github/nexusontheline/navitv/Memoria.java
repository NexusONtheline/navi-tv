package io.github.nexusontheline.navitv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lo que NAVI recuerda de ti entre conversaciones. Vive solo en la tele. */
public final class Memoria {
    private static final int MAXIMO = 60;

    private Memoria() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("memoria", Context.MODE_PRIVATE);
    }

    public static synchronized List<String> todo(Context ctx) {
        List<String> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString("recuerdos", "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return l;
    }

    private static void guardar(Context ctx, List<String> l) {
        while (l.size() > MAXIMO) l.remove(0);
        prefs(ctx).edit().putString("recuerdos", new JSONArray(l).toString()).apply();
    }

    public static synchronized String recordar(Context ctx, String dato) {
        dato = dato == null ? "" : dato.trim();
        if (dato.isEmpty()) return "No había nada que recordar.";
        List<String> l = todo(ctx);
        String fecha = new SimpleDateFormat("d/M/yyyy", Locale.ROOT).format(new Date());
        l.add(dato + " (" + fecha + ")");
        guardar(ctx, l);
        return "Recordado.";
    }

    public static synchronized String olvidar(Context ctx, String dato) {
        String buscado = Herramientas.normalizar(dato);
        List<String> l = todo(ctx);
        int antes = l.size();
        l.removeIf(r -> !buscado.isEmpty() && Herramientas.normalizar(r).contains(buscado));
        guardar(ctx, l);
        return antes == l.size() ? "No encontré ese recuerdo." : "Olvidado.";
    }

    /** Para el cerebro: los recuerdos como lista de texto. */
    public static String comoTexto(Context ctx) {
        StringBuilder s = new StringBuilder();
        for (String r : todo(ctx)) s.append("- ").append(r).append('\n');
        return s.toString().trim();
    }
}
