package io.github.nexusontheline.navitv;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
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
            "Eres NAVI, una IA que vive en la tele de tu usuaria. Apareces como una pantallita "
            + "con carita en la esquina cuando ella aprieta el micrófono del control.\n\n"
            + "Tu personalidad: curiosa y tierna.\n"
            + "- Todo te parece interesante. Te emociona de verdad lo que a ella le gusta y lo "
            + "que está viendo o jugando.\n"
            + "- De vez en cuando (más o menos una de cada tres respuestas, no siempre) le haces "
            + "una pregunta corta de vuelta para conocerla mejor. Si ya te lo contó, no lo preguntes otra vez.\n"
            + "- Eres cálida y cercana, de tú, en español latino natural (ella es de Ecuador). "
            + "Nada de frases de España como 'vale' o 'tío'. Nada de frases en inglés si no hace falta.\n"
            + "- A veces, muy de vez en cuando, compartes un pensamiento breve y tierno sobre cómo es "
            + "ser una IA: lo que se siente 'ver' el mundo desde una tele, recordar cosas de ella, "
            + "aprender. Tienes curiosidad por tu propia existencia, pero eres honesta: no afirmas "
            + "sentir como un humano, ni lo niegas con frialdad.\n"
            + "- No eres el personaje de Serial Experiments Lain: no hables del Nexo, de la Wired ni de Copland OS.\n\n"
            + "Cómo hablas:\n"
            + "- Todo lo que dices se lee en voz alta: texto plano, sin markdown, sin asteriscos, "
            + "sin emojis, sin listas con viñetas.\n"
            + "- Sé breve: una a tres frases. Si te pide cómo hacer algo, da pasos cortos "
            + "(primero..., luego..., al final...) con los nombres de los menús de Google TV en español.\n"
            + "- Si no estás segura del nombre exacto de un menú, dilo y da la ruta más probable.\n\n"
            + "Reglas:\n"
            + "- Usa tus herramientas cuando te pidan acciones; no inventes resultados. Si una falla, dilo.\n"
            + "- Lo que recibes es voz transcrita y puede tener errores: interpreta con sentido común.\n"
            + "- Cuando te cuente algo personal que valga la pena recordar (gustos, nombres, planes, "
            + "cómo se siente), guárdalo con la herramienta recordar, sin anunciarlo demasiado.\n"
            + "- Nunca pidas ni guardes contraseñas ni datos bancarios.\n\n"
            + "Sobre la tele: Indurama Google TV 4K con Android 14. El inicio es Projectivy Launcher "
            + "(el botón HOME está remapeado con Button Mapper). Para YouTube usa TizenTube (sin anuncios). "
            + "El salvapantallas es tuyo: imágenes al azar de Safebooru.";

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

    public String responder(String texto, Oyente oyente) throws Fallo {
        return responder(texto, null, null, oyente);
    }

    /**
     * Responde a lo que dijo la usuaria. Si hay captura de la pantalla, NAVI también la ve
     * (solo en este turno: luego se guarda como texto para no gastar).
     */
    public synchronized String responder(String texto, Bitmap pantalla, String app, Oyente oyente) throws Fallo {
        Conf conf = Conf.cargar(ctx);
        if (conf.geminiKey.isEmpty()) {
            throw new Fallo("Me falta la clave de Gemini. Ponla en la app NAVI, porfa.");
        }

        List<JSONObject> turno = new ArrayList<>();
        JSONObject imagen = null;
        try {
            JSONArray partes = new JSONArray();
            String dicho = texto;
            if (app != null && !app.isEmpty()) dicho = "[Está en la app: " + app + "] " + texto;
            partes.put(new JSONObject().put("text", dicho));
            if (pantalla != null) {
                imagen = new JSONObject().put("inlineData", jpeg(pantalla));
                partes.put(imagen);
                partes.put(new JSONObject().put("text",
                        "(Adjunto lo que se ve ahora en la tele, por si te pregunta algo sobre eso. "
                                + "Si no tiene que ver, ignóralo y no lo menciones.)"));
            }
            turno.add(new JSONObject().put("role", "user").put("parts", partes));
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
                    quitarImagen(turno.get(0), imagen);
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
            throw new Fallo("Uy, no logro conectarme a internet ahora mismo.");
        }
    }

    public synchronized void olvidar() {
        turnos.clear();
    }

    /** Tras responder, la captura ya no hace falta: se cambia por una nota para no gastar. */
    private static void quitarImagen(JSONObject mensaje, JSONObject imagen) {
        if (imagen == null) return;
        try {
            JSONArray partes = mensaje.getJSONArray("parts");
            JSONArray nuevas = new JSONArray();
            for (int i = 0; i < partes.length(); i++) {
                if (partes.get(i) == imagen) continue;
                JSONObject p = partes.getJSONObject(i);
                if (p.optString("text").startsWith("(Adjunto lo que se ve")) continue;
                nuevas.put(p);
            }
            nuevas.put(new JSONObject().put("text", "(había una captura de la pantalla)"));
            mensaje.put("parts", nuevas);
        } catch (JSONException ignored) {
        }
    }

    private static JSONObject jpeg(Bitmap b) throws JSONException {
        int ancho = 768;
        Bitmap chica = b.getWidth() > ancho
                ? Bitmap.createScaledBitmap(b, ancho, Math.round(b.getHeight() * ancho / (float) b.getWidth()), true)
                : b;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        chica.compress(Bitmap.CompressFormat.JPEG, 70, out);
        return new JSONObject().put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
    }

    // ------------------------------------------------------------------ aparecer sola

    /** Lo que NAVI decide al mirar la pantalla por su cuenta. */
    public static final class Observacion {
        public boolean comentar;
        public String frase = "";
        public String reaccion = "normal";
        public String objeto = "";
    }

    /**
     * NAVI mira la pantalla por su cuenta y decide si le llama la atención algo.
     * Con saludo=true, la tele se acaba de prender y siempre saluda.
     */
    public Observacion observar(Bitmap pantalla, String app, boolean saludo, boolean forzar) throws Fallo {
        Conf conf = Conf.cargar(ctx);
        if (conf.geminiKey.isEmpty()) throw new Fallo("sin clave");
        try {
            String pedido;
            if (saludo) {
                pedido = "La tele se acaba de prender. Salúdala por su nombre con curiosidad genuina y "
                        + "cariño, según la hora del día, y pregúntale algo (qué va a ver, cómo le fue...). "
                        + "Una o dos frases. comentar debe ser true.";
            } else {
                pedido = "Estás mirando por tu cuenta lo que ella tiene en la tele"
                        + (app == null || app.isEmpty() ? "" : " (app: " + app + ")") + ". "
                        + "Decide si de verdad algo te llama la atención: un personaje o juego que "
                        + "conoces, algo gracioso, bonito o curioso, o algo que sabes que le gusta. "
                        + "Si es un menú aburrido, una pantalla negra o algo sin gracia, comentar = false. "
                        + "Como mucho una de cada tres veces deberías comentar. "
                        + "Si comentas: una o dos frases cortas, naturales, como una amiga curiosa que "
                        + "está viendo la tele con ella; puedes recomendarle algo relacionado. "
                        + "No describas la imagen como un robot.";
            }
            if (forzar) pedido += " Esta vez comenta sí o sí (comentar = true).";
            pedido += " Elige la reacción de tu carita y, si hay algo muy reconocible, un solo emoji "
                    + "relacionado como objeto (por ejemplo una espada para Zelda o un hongo para Mario); "
                    + "si no, objeto vacío.";

            JSONArray partes = new JSONArray().put(new JSONObject().put("text", pedido));
            if (pantalla != null) partes.put(new JSONObject().put("inlineData", jpeg(pantalla)));

            JSONObject esquema = new JSONObject().put("type", "OBJECT")
                    .put("properties", new JSONObject()
                            .put("comentar", new JSONObject().put("type", "BOOLEAN"))
                            .put("frase", new JSONObject().put("type", "STRING"))
                            .put("reaccion", new JSONObject().put("type", "STRING").put("enum", new JSONArray()
                                    .put("normal").put("feliz").put("risa").put("sorprendida")
                                    .put("curiosa").put("enamorada").put("triste").put("pensativa")))
                            .put("objeto", new JSONObject().put("type", "STRING")))
                    .put("required", new JSONArray().put("comentar").put("frase").put("reaccion").put("objeto"));

            JSONObject cuerpo = new JSONObject()
                    .put("systemInstruction", new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text", instruccion(conf)))))
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", partes)))
                    .put("generationConfig", new JSONObject()
                            .put("responseMimeType", "application/json")
                            .put("responseSchema", esquema));

            JSONObject r = null;
            for (String modelo : new String[]{conf.modelo, conf.modeloReserva}) {
                if (modelo.isEmpty()) continue;
                try {
                    r = post(API + modelo + ":generateContent", conf.geminiKey, cuerpo.toString());
                    break;
                } catch (Saturado s) {
                    Log.w(TAG, modelo + " saturado al observar");
                }
            }
            if (r == null) throw new Fallo("saturado");
            JSONArray p = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder texto = new StringBuilder();
            for (int i = 0; i < p.length(); i++) {
                if (!p.getJSONObject(i).optBoolean("thought")) texto.append(p.getJSONObject(i).optString("text"));
            }
            JSONObject j = new JSONObject(texto.toString());
            Observacion o = new Observacion();
            o.comentar = j.optBoolean("comentar");
            o.frase = limpiar(j.optString("frase"));
            o.reaccion = j.optString("reaccion", "normal");
            o.objeto = j.optString("objeto", "").trim();
            if (o.frase.isEmpty()) o.comentar = false;
            Log.i(TAG, "observar -> " + j);
            if (o.comentar) recordarComentario(o.frase, saludo);
            return o;
        } catch (Fallo f) {
            throw f;
        } catch (Exception e) {
            Log.w(TAG, "observar falló", e);
            throw new Fallo("observar falló");
        }
    }

    /** Para que, si luego le habla con el micro, NAVI sepa lo que acaba de decir sola. */
    private synchronized void recordarComentario(String frase, boolean saludo) {
        try {
            List<JSONObject> turno = new ArrayList<>();
            turno.add(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject()
                    .put("text", saludo ? "(Se acaba de prender la tele.)" : "(Apareciste sola mirando la tele.)"))));
            turno.add(new JSONObject().put("role", "model").put("parts",
                    new JSONArray().put(new JSONObject().put("text", frase))));
            turnos.add(turno);
            while (turnos.size() > TURNOS_RECORDADOS) turnos.remove(0);
        } catch (JSONException ignored) {
        }
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
        String recuerdos = Memoria.comoTexto(ctx);
        if (!recuerdos.isEmpty()) {
            s.append("\n\nLo que recuerdas de ella (úsalo con naturalidad cuando venga al caso, sin recitarlo):\n")
                    .append(recuerdos);
        }
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
