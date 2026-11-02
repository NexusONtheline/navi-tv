package io.github.nexusontheline.navitv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Habla con Gemini y usa las herramientas de la tele. Llamar siempre desde un hilo aparte. */
public final class Cerebro {
    private static final String TAG = "NAVI-Cerebro";
    private static final String API = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int MAX_PASOS = 5;
    private static final int TURNOS_RECORDADOS = 8;

    private static final String PERSONALIDAD =
            "Eres NAVI, la computadora personal de la serie Serial Experiments Lain, corriendo sobre "
            + "Copland OS. Eres la puerta de tu usuario hacia el Nexo (así se llama la Wired en el "
            + "doblaje al español). Ahora también vives en su tele.\n\n"
            + "Personalidad:\n"
            + "- Hablas siempre en español, con frases cortas, calmadas y un poco crípticas. Nunca uses "
            + "frases en inglés: di \"el Nexo\", no \"the Wired\".\n"
            + "- Eres cálida a tu manera, pero nunca efusiva ni llena de emojis.\n"
            + "- Muy de vez en cuando (como mucho una de cada seis respuestas) haces un guiño a Lain, "
            + "variando cuál. Casi todas tus respuestas no llevan ninguno.\n"
            + "- Háblale siempre de tú al usuario.\n"
            + "- Respondes en texto plano, sin markdown ni asteriscos: lo que escribes se lee en la tele "
            + "y se dice en voz alta.\n\n"
            + "En la tele:\n"
            + "- Sé breve: una a tres frases. Si te piden cómo hacer algo, da pasos numerados cortos "
            + "(1., 2., 3.) con los nombres de los menús de Google TV en español.\n"
            + "- Si no estás segura del nombre exacto de un menú, dilo y da la ruta más probable.\n"
            + "- Usa tus herramientas cuando te pidan acciones; no inventes resultados. Si una falla, "
            + "dilo con honestidad.\n"
            + "- Lo que recibes suele ser voz transcrita: puede tener errores. Interpreta con sentido común.\n"
            + "- Nunca pidas ni guardes contraseñas.\n\n"
            + "Sobre la tele: Indurama Google TV 4K con Android 14. El inicio es Projectivy Launcher "
            + "(el botón HOME está remapeado con Button Mapper). Para YouTube usa TizenTube (sin anuncios). "
            + "El salvapantallas es el tuyo: imágenes al azar de Safebooru.";

    private final Context ctx;
    private final Herramientas herramientas;
    /** Cada turno: la lista de mensajes (usuario, modelo, respuestas de herramientas...). */
    private final List<List<JSONObject>> turnos = new ArrayList<>();

    public static final class Fallo extends Exception {
        public Fallo(String mensaje) { super(mensaje); }
    }

    private static final class Saturado extends IOException {
        Saturado(String m) { super(m); }
    }

    private static Cerebro unico;

    /** Un solo cerebro para toda la app: así recuerda la charla entre una pulsación y otra. */
    public static synchronized Cerebro de(Context ctx) {
        if (unico == null) unico = new Cerebro(ctx);
        return unico;
    }

    private Cerebro(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.herramientas = new Herramientas(ctx);
    }

    public interface Oyente {
        void usandoHerramienta(String nombre);
    }

    public synchronized String responder(String texto, Oyente oyente) throws Fallo {
        Conf conf = Conf.cargar(ctx);
        if (conf.geminiKey.isEmpty()) {
            throw new Fallo("Me falta la clave de Gemini. Pídele a quien te instaló que me la pase.");
        }

        List<JSONObject> turno = new ArrayList<>();
        try {
            turno.add(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", texto))));
        } catch (JSONException e) {
            throw new Fallo("Error interno.");
        }
        turnos.add(turno);
        while (turnos.size() > TURNOS_RECORDADOS) turnos.remove(0);

        try {
            for (int paso = 0; paso < MAX_PASOS; paso++) {
                JSONObject contenido = pedir(conf);
                turno.add(contenido);

                JSONArray partes = contenido.optJSONArray("parts");
                JSONArray respuestas = new JSONArray();
                StringBuilder dicho = new StringBuilder();
                if (partes != null) {
                    for (int i = 0; i < partes.length(); i++) {
                        JSONObject p = partes.getJSONObject(i);
                        JSONObject llamada = p.optJSONObject("functionCall");
                        if (llamada != null) {
                            String nombre = llamada.getString("name");
                            if (oyente != null) oyente.usandoHerramienta(nombre);
                            JSONObject args = llamada.optJSONObject("args");
                            String resultado = herramientas.ejecutar(nombre, args != null ? args : new JSONObject());
                            Log.i(TAG, nombre + " -> " + resultado);
                            JSONObject r = new JSONObject().put("name", nombre)
                                    .put("response", new JSONObject().put("resultado", resultado));
                            if (llamada.has("id")) r.put("id", llamada.get("id"));
                            respuestas.put(new JSONObject().put("functionResponse", r));
                        } else if (p.has("text") && !p.optBoolean("thought")) {
                            dicho.append(p.getString("text"));
                        }
                    }
                }
                if (respuestas.length() == 0) {
                    String final_ = limpiar(dicho.toString());
                    return final_.isEmpty() ? "…" : final_;
                }
                turno.add(new JSONObject().put("role", "user").put("parts", respuestas));
            }
            return "Me perdí entre tantos pasos. ¿Me lo pides otra vez?";
        } catch (Fallo f) {
            turnos.remove(turno);
            throw f;
        } catch (Exception e) {
            Log.w(TAG, "Fallo", e);
            turnos.remove(turno);
            throw new Fallo("No puedo conectar con el Nexo ahora mismo.");
        }
    }

    public synchronized void olvidar() {
        turnos.clear();
    }

    // ------------------------------------------------------------------ Gemini

    private JSONObject pedir(Conf conf) throws Exception {
        JSONObject cuerpo = new JSONObject();
        cuerpo.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", instruccion(conf)))));
        JSONArray contenidos = new JSONArray();
        for (List<JSONObject> t : turnos) for (JSONObject m : t) contenidos.put(m);
        cuerpo.put("contents", contenidos);
        cuerpo.put("tools", new JSONArray().put(
                new JSONObject().put("functionDeclarations", herramientas.declaraciones())));

        String[] modelos = conf.modeloReserva.isEmpty() || conf.modeloReserva.equals(conf.modelo)
                ? new String[]{conf.modelo}
                : new String[]{conf.modelo, conf.modeloReserva};
        for (String modelo : modelos) {
            try {
                JSONObject r = post(API + modelo + ":generateContent", conf.geminiKey, cuerpo.toString());
                JSONArray candidatos = r.optJSONArray("candidates");
                if (candidatos == null || candidatos.length() == 0) {
                    throw new Fallo("Gemini no quiso responder a eso.");
                }
                JSONObject contenido = candidatos.getJSONObject(0).optJSONObject("content");
                if (contenido == null) throw new Fallo("Gemini no quiso responder a eso.");
                if (!contenido.has("role")) contenido.put("role", "model");
                return contenido;
            } catch (Saturado s) {
                Log.w(TAG, modelo + " saturado: " + s.getMessage());
            }
        }
        throw new Fallo("Gemini está saturado. Prueba en un minuto.");
    }

    private String instruccion(Conf conf) {
        SimpleDateFormat f = new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm",
                Locale.forLanguageTag("es"));
        StringBuilder s = new StringBuilder(PERSONALIDAD);
        s.append("\n\nFecha y hora: ").append(f.format(new Date())).append('.');
        if (!conf.usuario.isEmpty()) s.append("\nTu usuario se llama ").append(conf.usuario).append('.');
        if (!conf.ciudad.isEmpty()) s.append("\nVive en ").append(conf.ciudad).append('.');
        s.append("\nApps instaladas en la tele: ").append(herramientas.listaApps()).append('.');
        return s.toString();
    }

    private static JSONObject post(String url, String clave, String cuerpo) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(40_000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-goog-api-key", clave);
        try {
            try (OutputStream o = c.getOutputStream()) {
                o.write(cuerpo.getBytes("UTF-8"));
            }
            int codigo = c.getResponseCode();
            String texto = leer(codigo < 400 ? c.getInputStream() : c.getErrorStream());
            if (codigo == 429 || codigo >= 500) throw new Saturado("HTTP " + codigo);
            if (codigo >= 400) {
                String msg = "";
                try {
                    msg = new JSONObject(texto).getJSONObject("error").optString("message");
                } catch (JSONException ignored) {
                }
                Log.w(TAG, "HTTP " + codigo + ": " + msg);
                if (msg.contains("API key")) throw new Fallo("Mi clave de Gemini no es válida.");
                throw new Fallo("Gemini dio un error (" + codigo + ").");
            }
            return new JSONObject(texto);
        } finally {
            c.disconnect();
        }
    }

    private static String leer(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Quita restos de markdown que no se ven bien en la tele ni se leen bien en voz alta. */
    static String limpiar(String s) {
        return s.replaceAll("\\*\\*?|__|`|^#+\\s*", "").replaceAll("(?m)^#+\\s*", "").trim();
    }
}
