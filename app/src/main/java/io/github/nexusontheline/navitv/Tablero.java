package io.github.nexusontheline.navitv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Los widgets del salvapantallas, en tarjetitas de cristal oscuro:
 * clima, anime de hoy, ahora suena, un gif, una frase de anime, el nombre de la
 * ilustración, NAVI durmiendo con gorrito y el reloj.
 */
final class Tablero {
    private static final String TAG = "NAVI-Tablero";
    private static final long MIN = 60_000L;

    private static final int[] GORROS = {
            0xFFE0457A, 0xFF7C5CFF, 0xFF3FA7D6, 0xFF4CAF7A, 0xFFF2A541, 0xFFC77DFF, 0xFFE85D5D,
    };
    private static final String[] COSAS = {
            "🌙", "⭐", "🧸", "☕", "🍙", "🎮", "📚", "🐱", "🍵", "🌸", "🎧", "🍡", "🪐", "🕯️", "🍪", "💤",
    };

    private final Context ctx;
    private final Conf conf;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService red = Executors.newFixedThreadPool(3);
    private final Random azar = new Random();
    private final float d;
    private final FrameLayout raiz;
    private boolean activo;

    private LinearLayout tarjetaClima, tarjetaAnime, tarjetaMusica, tarjetaFrase, tarjetaIlus;
    private FrameLayout tarjetaGif;
    private TextView climaIcono, climaTexto, climaDetalle, animeLista;
    private TextView musicaTitulo, musicaArtista, fraseTexto, fraseAutor, ilusNombre, ilusSerie;
    private ImageView musicaArte, gifVista;
    private Carita navi;
    private TextView naviCosa, naviGlobo;
    private String etiquetas = "";
    private double lat = Double.NaN, lon;
    private String musicaActual = "";

    Tablero(Context ctx, Conf conf) {
        this.ctx = ctx;
        this.conf = conf;
        this.d = ctx.getResources().getDisplayMetrics().density;
        raiz = new FrameLayout(ctx);
        raiz.setClipChildren(false);
        int m = px(28);

        if (conf.widgets) {
            // Arriba a la izquierda: clima y anime de hoy
            LinearLayout izqArriba = columna();
            tarjetaClima = tarjeta();
            LinearLayout filaClima = new LinearLayout(ctx);
            filaClima.setGravity(Gravity.CENTER_VERTICAL);
            climaIcono = texto("", 30, Color.WHITE);
            filaClima.addView(climaIcono);
            LinearLayout climaTextos = columna();
            climaTextos.setPadding(px(10), 0, 0, 0);
            climaTexto = texto("", 17, Color.WHITE);
            climaDetalle = texto("", 12, 0xBBFFFFFF);
            climaTextos.addView(climaTexto);
            climaTextos.addView(climaDetalle);
            filaClima.addView(climaTextos);
            tarjetaClima.addView(filaClima);
            izqArriba.addView(tarjetaClima, conMargen());

            tarjetaAnime = tarjeta();
            tarjetaAnime.addView(etiqueta("PRÓXIMOS EPISODIOS"));
            animeLista = texto("", 13, Color.WHITE);
            animeLista.setHorizontallyScrolling(false);
            animeLista.setLineSpacing(px(3), 1f);
            tarjetaAnime.addView(animeLista);
            izqArriba.addView(tarjetaAnime, conMargen());
            ocultar(tarjetaClima, tarjetaAnime);
            raiz.addView(izqArriba, posicion(Gravity.TOP | Gravity.START, m, px(290)));

            // Arriba a la derecha: ahora suena y el gif
            LinearLayout derArriba = columna();
            derArriba.setGravity(Gravity.END);
            tarjetaMusica = tarjeta();
            tarjetaMusica.setOrientation(LinearLayout.HORIZONTAL);
            tarjetaMusica.setGravity(Gravity.CENTER_VERTICAL);
            musicaArte = new ImageView(ctx);
            musicaArte.setScaleType(ImageView.ScaleType.CENTER_CROP);
            musicaArte.setClipToOutline(true);
            musicaArte.setBackground(redondeado(0xFF222233, 8));
            tarjetaMusica.addView(musicaArte, new LinearLayout.LayoutParams(px(46), px(46)));
            LinearLayout musicaTextos = columna();
            musicaTextos.setPadding(px(10), 0, 0, 0);
            musicaTextos.addView(etiqueta("AHORA SUENA"));
            musicaTitulo = texto("", 13, Color.WHITE);
            musicaTitulo.setSingleLine(true);
            musicaTitulo.setEllipsize(TextUtils.TruncateAt.END);
            musicaTitulo.setMaxWidth(px(190));
            musicaArtista = texto("", 11, 0xBBFFFFFF);
            musicaArtista.setSingleLine(true);
            musicaArtista.setEllipsize(TextUtils.TruncateAt.END);
            musicaArtista.setMaxWidth(px(190));
            musicaTextos.addView(musicaTitulo);
            musicaTextos.addView(musicaArtista);
            tarjetaMusica.addView(musicaTextos);
            derArriba.addView(tarjetaMusica, conMargen());

            tarjetaGif = new FrameLayout(ctx);
            tarjetaGif.setBackground(redondeado(0x99101018, 16));
            tarjetaGif.setClipToOutline(true);
            gifVista = new ImageView(ctx);
            gifVista.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tarjetaGif.addView(gifVista, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams lpGif = new LinearLayout.LayoutParams(px(190), px(140));
            lpGif.bottomMargin = px(10);
            derArriba.addView(tarjetaGif, lpGif);
            ocultar(tarjetaMusica, tarjetaGif);
            raiz.addView(derArriba, posicion(Gravity.TOP | Gravity.END, m, -2));

            // Abajo a la izquierda: frase de anime y nombre de la ilustración
            LinearLayout izqAbajo = columna();
            tarjetaFrase = tarjeta();
            fraseTexto = texto("", 14, Color.WHITE);
            fraseTexto.setTypeface(null, android.graphics.Typeface.ITALIC);
            fraseAutor = texto("", 11, 0xBBFFFFFF);
            fraseAutor.setPadding(0, px(4), 0, 0);
            tarjetaFrase.addView(fraseTexto);
            tarjetaFrase.addView(fraseAutor);
            izqAbajo.addView(tarjetaFrase, conMargen());

            tarjetaIlus = tarjeta();
            tarjetaIlus.addView(etiqueta("EN LA IMAGEN"));
            ilusNombre = texto("", 14, Color.WHITE);
            ilusSerie = texto("", 11, 0xBBFFFFFF);
            tarjetaIlus.addView(ilusNombre);
            tarjetaIlus.addView(ilusSerie);
            izqAbajo.addView(tarjetaIlus, new LinearLayout.LayoutParams(-2, -2));
            ocultar(tarjetaFrase, tarjetaIlus);
            raiz.addView(izqAbajo, posicion(Gravity.BOTTOM | Gravity.START, m, px(330)));
        }

        // Abajo a la derecha: NAVI durmiendo y el reloj
        LinearLayout derAbajo = new LinearLayout(ctx);
        derAbajo.setOrientation(LinearLayout.HORIZONTAL);
        derAbajo.setGravity(Gravity.BOTTOM);
        derAbajo.setClipChildren(false);
        if (conf.widgets) {
            LinearLayout grupo = columna();
            grupo.setGravity(Gravity.END);
            grupo.setClipChildren(false);
            naviGlobo = texto("", 12, Color.WHITE);
            naviGlobo.setBackground(redondeado(0xCC18132A, 12));
            naviGlobo.setPadding(px(10), px(6), px(10), px(6));
            naviGlobo.setMaxWidth(px(230));
            naviGlobo.setVisibility(View.INVISIBLE);
            grupo.addView(naviGlobo);
            LinearLayout fila = new LinearLayout(ctx);
            fila.setGravity(Gravity.BOTTOM);
            fila.setClipChildren(false);
            naviCosa = texto("", 28, Color.WHITE);
            naviCosa.setShadowLayer(8, 0, 0, Color.BLACK);
            fila.addView(naviCosa);
            navi = new Carita(ctx);
            navi.setEstado(Carita.Estado.DORMIDA);
            fila.addView(navi, new LinearLayout.LayoutParams(px(104), px(118)));
            grupo.addView(fila);
            LinearLayout.LayoutParams lpGrupo = new LinearLayout.LayoutParams(-2, -2);
            lpGrupo.rightMargin = px(18);
            derAbajo.addView(grupo, lpGrupo);
        }
        if (conf.mostrarReloj) {
            // Con widgets, el reloj también va en su tarjeta (si no, en imágenes claras no se lee)
            LinearLayout reloj = conf.widgets ? tarjeta() : columna();
            reloj.setGravity(Gravity.END);
            TextClock hora = new TextClock(ctx);
            hora.setFormat24Hour("HH:mm");
            hora.setFormat12Hour("h:mm");
            hora.setTextSize(48);
            hora.setTextColor(Color.WHITE);
            hora.setShadowLayer(10, 0, 0, Color.BLACK);
            reloj.addView(hora);
            TextClock fecha = new TextClock(ctx);
            fecha.setFormat24Hour("EEEE d 'de' MMMM");
            fecha.setFormat12Hour("EEEE d 'de' MMMM");
            fecha.setTextSize(16);
            fecha.setTextColor(0xDDFFFFFF);
            fecha.setShadowLayer(8, 0, 0, Color.BLACK);
            reloj.addView(fecha);
            derAbajo.addView(reloj);
        }
        raiz.addView(derAbajo, posicion(Gravity.BOTTOM | Gravity.END, m, -2));
    }

    View vista() {
        return raiz;
    }

    void empezar() {
        if (activo || !conf.widgets) return;
        activo = true;
        ui.post(clima);
        ui.post(anime);
        ui.post(frase);
        ui.postDelayed(gif, 1500);
        ui.post(musica);
        ui.post(cambiarGorro);
        ui.postDelayed(abrirOjo, (3 + azar.nextInt(3)) * MIN);
    }

    void parar() {
        activo = false;
        ui.removeCallbacksAndMessages(null);
        red.shutdownNow();
        if (gifVista != null && gifVista.getDrawable() instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) gifVista.getDrawable()).stop();
        }
    }

    /** Pase avisa con las etiquetas de la imagen nueva. */
    void nuevaImagen(String etiquetasImagen) {
        etiquetas = etiquetasImagen == null ? "" : etiquetasImagen;
        if (!activo || tarjetaIlus == null) return;
        String estas = etiquetas;
        red.execute(() -> nombreDeLaIlustracion(estas));
    }

    // ------------------------------------------------------------ clima

    private final Runnable clima = new Runnable() {
        @Override public void run() {
            if (!activo) return;
            red.execute(() -> {
                try {
                    if (Double.isNaN(lat)) {
                        if (conf.ciudad.trim().isEmpty()) return;  // sin ciudad no hay clima
                        String nombre = conf.ciudad.replace("'", "").split(",")[0].trim();
                        JSONObject g = new JSONObject(Herramientas.get(
                                "https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&name="
                                        + URLEncoder.encode(nombre, "UTF-8")));
                        JSONObject l = g.getJSONArray("results").getJSONObject(0);
                        lat = l.getDouble("latitude");
                        lon = l.getDouble("longitude");
                    }
                    JSONObject t = new JSONObject(Herramientas.get(String.format(Locale.ROOT,
                            "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f&timezone=auto"
                                    + "&forecast_days=1&current=temperature_2m,weather_code,is_day"
                                    + "&daily=temperature_2m_max,temperature_2m_min", lat, lon)));
                    JSONObject c = t.getJSONObject("current");
                    JSONObject dd = t.getJSONObject("daily");
                    int codigo = c.optInt("weather_code");
                    boolean dia = c.optInt("is_day", 1) == 1;
                    String icono = iconoClima(codigo, dia);
                    String linea = Math.round(c.optDouble("temperature_2m")) + "° · " + descripcion(codigo);
                    String detalle = "Máx " + Math.round(dd.getJSONArray("temperature_2m_max").optDouble(0))
                            + "° · Mín " + Math.round(dd.getJSONArray("temperature_2m_min").optDouble(0)) + "°";
                    ui.post(() -> {
                        climaIcono.setText(icono);
                        climaTexto.setText(linea);
                        climaDetalle.setText(detalle);
                        mostrar(tarjetaClima);
                    });
                } catch (Exception e) {
                    Log.w(TAG, "clima", e);
                }
            });
            ui.postDelayed(this, 30 * MIN);
        }
    };

    private static String iconoClima(int c, boolean dia) {
        if (c == 0) return dia ? "☀️" : "🌙";
        if (c <= 2) return dia ? "🌤️" : "☁️";
        if (c == 3) return "☁️";
        if (c <= 48) return "🌫️";
        if (c <= 57) return "🌦️";
        if (c <= 67 || (c >= 80 && c <= 82)) return "🌧️";
        if (c <= 77) return "❄️";
        return "⛈️";
    }

    private static String descripcion(int c) {
        if (c == 0) return "Despejado";
        if (c <= 2) return "Algo nublado";
        if (c == 3) return "Nublado";
        if (c <= 48) return "Neblina";
        if (c <= 57) return "Llovizna";
        if (c <= 67) return "Lluvia";
        if (c <= 77) return "Nieve";
        if (c <= 82) return "Chubascos";
        return "Tormenta";
    }

    // ------------------------------------------------------------ anime de hoy (AniList)

    private final Runnable anime = new Runnable() {
        @Override public void run() {
            if (!activo) return;
            red.execute(() -> {
                try {
                    // Los próximos episodios (de ahora a 24 h)
                    long ini = System.currentTimeMillis() / 1000;
                    long fin = ini + 86400;
                    String q = "query{Page(perPage:30){airingSchedules(airingAt_greater:" + ini
                            + ",airingAt_lesser:" + fin + ",sort:TIME){airingAt episode media{"
                            + "title{romaji english} popularity isAdult}}}}";
                    JSONObject r = new JSONObject(postJson("https://graphql.anilist.co",
                            new JSONObject().put("query", q).toString()));
                    JSONArray lista = r.getJSONObject("data").getJSONObject("Page").getJSONArray("airingSchedules");
                    List<JSONObject> buenas = new ArrayList<>();
                    for (int i = 0; i < lista.length(); i++) {
                        JSONObject a = lista.getJSONObject(i);
                        if (!a.getJSONObject("media").optBoolean("isAdult")) buenas.add(a);
                    }
                    // Las 4 más populares, en orden de hora
                    buenas.sort((a, b) -> Integer.compare(b.optJSONObject("media").optInt("popularity"),
                            a.optJSONObject("media").optInt("popularity")));
                    List<JSONObject> top = new ArrayList<>(buenas.subList(0, Math.min(4, buenas.size())));
                    top.sort((a, b) -> Long.compare(a.optLong("airingAt"), b.optLong("airingAt")));
                    SimpleDateFormat hora = new SimpleDateFormat("HH:mm", Locale.ROOT);
                    StringBuilder s = new StringBuilder();
                    for (JSONObject a : top) {
                        JSONObject t = a.getJSONObject("media").getJSONObject("title");
                        String titulo = t.optString("english");
                        if (titulo.isEmpty() || titulo.equals("null")) titulo = t.optString("romaji");
                        if (titulo.length() > 22) titulo = titulo.substring(0, 21).trim() + "…";
                        if (s.length() > 0) s.append('\n');
                        Date cuando = new Date(a.optLong("airingAt") * 1000);
                        s.append(hora.format(cuando)).append("  ").append(titulo)
                                .append("  #").append(a.optInt("episode"));
                    }
                    String texto = s.toString();
                    ui.post(() -> {
                        if (texto.isEmpty()) return;
                        animeLista.setText(texto);
                        mostrar(tarjetaAnime);
                    });
                } catch (Exception e) {
                    Log.w(TAG, "anime", e);
                }
            });
            ui.postDelayed(this, 120 * MIN);
        }
    };

    // ------------------------------------------------------------ frase de anime

    private final Runnable frase = new Runnable() {
        @Override public void run() {
            if (!activo) return;
            String[] f = Frases.TODAS[azar.nextInt(Frases.TODAS.length)];
            fraseTexto.setText("«" + f[0] + "»");
            fraseAutor.setText("— " + (f[1].isEmpty() ? f[2] : f[1] + ", " + f[2]));
            if (tarjetaFrase.getVisibility() == View.VISIBLE) {
                tarjetaFrase.setAlpha(0f);
                tarjetaFrase.animate().alpha(1f).setDuration(800).start();
            } else {
                mostrar(tarjetaFrase);
            }
            ui.postDelayed(this, 10 * MIN);
        }
    };

    // ------------------------------------------------------------ nombre de la ilustración

    private void nombreDeLaIlustracion(String tags) {
        try {
            Set<String> nombres = new LinkedHashSet<>();
            for (String t : tags.trim().split("\\s+")) if (!t.isEmpty() && nombres.size() < 60) nombres.add(t);
            if (nombres.isEmpty()) return;
            String url = "https://danbooru.donmai.us/tags.json?only=name,category&limit=100&search%5Bname_comma%5D="
                    + URLEncoder.encode(TextUtils.join(",", nombres), "UTF-8");
            JSONArray r = new JSONArray(Herramientas.get(url));
            List<String> personajes = new ArrayList<>();
            String serie = "", artista = "";
            for (int i = 0; i < r.length(); i++) {
                JSONObject t = r.getJSONObject(i);
                String n = t.getString("name");
                switch (t.optInt("category")) {
                    case 4: personajes.add(bonito(n)); break;
                    case 3: if (serie.isEmpty() || serie.equals("Original")) serie = bonito(n); break;
                    case 1: if (artista.isEmpty()) artista = n.replace('_', ' '); break;
                }
            }
            String nombre = personajes.isEmpty() ? (serie.isEmpty() ? "" : serie)
                    : TextUtils.join(" y ", personajes.subList(0, Math.min(2, personajes.size())));
            String debajo = personajes.isEmpty() ? "" : serie;
            if (!artista.isEmpty()) debajo = (debajo.isEmpty() ? "" : debajo + " · ") + "arte de " + artista;
            final String n1 = nombre, n2 = debajo;
            ui.post(() -> {
                if (n1.isEmpty() && n2.isEmpty()) {
                    tarjetaIlus.setVisibility(View.GONE);
                    return;
                }
                ilusNombre.setText(n1.isEmpty() ? "Ilustración original" : n1);
                ilusSerie.setText(n2);
                ilusSerie.setVisibility(n2.isEmpty() ? View.GONE : View.VISIBLE);
                mostrar(tarjetaIlus);
            });
        } catch (Exception e) {
            Log.w(TAG, "ilustración", e);
        }
    }

    /** hatsune_miku → Hatsune Miku; link_(zelda) → Link */
    private static String bonito(String etiqueta) {
        String s = etiqueta.replaceAll("_\\(.*\\)$", "").replace('_', ' ').trim();
        StringBuilder b = new StringBuilder();
        for (String p : s.split(" ")) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.toString();
    }

    // ------------------------------------------------------------ ahora suena

    private final Runnable musica = new Runnable() {
        @Override public void run() {
            if (!activo) return;
            try {
                MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                List<MediaController> sesiones = msm.getActiveSessions(new ComponentName(ctx, Escucha.class));
                MediaController sonando = null;
                for (MediaController mc : sesiones) {
                    PlaybackState ps = mc.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING && mc.getMetadata() != null) {
                        sonando = mc;
                        break;
                    }
                }
                if (sonando == null) {
                    tarjetaMusica.setVisibility(View.GONE);
                    musicaActual = "";
                } else {
                    MediaMetadata md = sonando.getMetadata();
                    String titulo = primero(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
                    String artista = primero(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                            MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                    if (!titulo.equals(musicaActual)) {
                        musicaActual = titulo;
                        musicaTitulo.setText(titulo);
                        musicaArtista.setText(artista);
                        musicaArtista.setVisibility(artista.isEmpty() ? View.GONE : View.VISIBLE);
                        Bitmap arte = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                        if (arte == null) arte = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                        if (arte == null) arte = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                        if (arte != null) {
                            musicaArte.setImageBitmap(arte);
                        } else {
                            musicaArte.setImageDrawable(null);
                            String uri = primero(md, MediaMetadata.METADATA_KEY_ART_URI,
                                    MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                            if (!uri.isEmpty()) red.execute(() -> cargarArte(uri));
                        }
                        mostrar(tarjetaMusica);
                    }
                }
            } catch (SecurityException e) {
                // Sin permiso de notificaciones: no hay widget de música
                tarjetaMusica.setVisibility(View.GONE);
            } catch (Exception e) {
                Log.w(TAG, "música", e);
            }
            ui.postDelayed(this, 4000);
        }
    };

    private static String primero(MediaMetadata md, String... claves) {
        for (String k : claves) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }

    private void cargarArte(String uri) {
        try {
            Bitmap b;
            if (uri.startsWith("http")) {
                byte[] datos = bajar(uri, 3 * 1024 * 1024);
                b = BitmapFactory.decodeByteArray(datos, 0, datos.length);
            } else {
                try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri))) {
                    b = BitmapFactory.decodeStream(in);
                }
            }
            if (b != null) ui.post(() -> musicaArte.setImageBitmap(b));
        } catch (Exception e) {
            Log.w(TAG, "arte", e);
        }
    }

    // ------------------------------------------------------------ gif random

    private final Runnable gif = new Runnable() {
        @Override public void run() {
            if (!activo) return;
            if (Build.VERSION.SDK_INT >= 28) red.execute(Tablero.this::nuevoGif);
            ui.postDelayed(this, 2 * MIN);
        }
    };

    private void nuevoGif() {
        try {
            JSONArray posts = new JSONArray(Herramientas.get(
                    "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1&limit=40&tags="
                            + URLEncoder.encode("animated_gif rating:general sort:random", "UTF-8")));
            for (int i = 0; i < posts.length(); i++) {
                JSONObject p = posts.getJSONObject(i);
                String url = p.optString("file_url");
                if (!url.endsWith(".gif") || p.optInt("width") > 900 || p.optInt("height") > 900) continue;
                byte[] datos;
                try {
                    datos = bajar(url, 4 * 1024 * 1024);
                } catch (IOException e) {
                    continue;  // muy grande o falló: probar el siguiente
                }
                Drawable dr = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(datos)));
                ui.post(() -> {
                    if (!activo) return;
                    Drawable viejo = gifVista.getDrawable();
                    if (viejo instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) viejo).stop();
                    gifVista.setImageDrawable(dr);
                    if (dr instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) dr).start();
                    gifVista.setAlpha(0f);
                    gifVista.animate().alpha(1f).setDuration(600).start();
                    mostrar(tarjetaGif);
                });
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "gif", e);
        }
    }

    // ------------------------------------------------------------ NAVI durmiendo

    private final Runnable cambiarGorro = new Runnable() {
        @Override public void run() {
            if (!activo) return;
            int color = GORROS[azar.nextInt(GORROS.length)];
            navi.setGorro(color, azar.nextBoolean());  // siempre con gorrito, de colores distintos
            naviCosa.setText(COSAS[azar.nextInt(COSAS.length)]);
            naviCosa.setAlpha(0f);
            naviCosa.animate().alpha(1f).setDuration(800).start();
            ui.postDelayed(this, (4 + azar.nextInt(4)) * MIN);
        }
    };

    /** De vez en cuando abre un ojito y dice algo cortito sobre la imagen. */
    private final Runnable abrirOjo = new Runnable() {
        @Override public void run() {
            if (!activo) return;
            ui.postDelayed(this, (6 + azar.nextInt(5)) * MIN);
            if (conf.geminiKey.isEmpty()) return;
            String sobre = etiquetas;
            red.execute(() -> {
                String dicho = Cerebro.de(ctx).frasecita(sobre);
                if (dicho == null || dicho.isEmpty()) return;
                ui.post(() -> {
                    if (!activo) return;
                    navi.abrirUnOjo(9000);
                    naviGlobo.setText(dicho);
                    naviGlobo.setAlpha(0f);
                    naviGlobo.setVisibility(View.VISIBLE);
                    naviGlobo.animate().alpha(1f).setDuration(500).start();
                    ui.postDelayed(() -> naviGlobo.animate().alpha(0f).setDuration(800)
                            .withEndAction(() -> naviGlobo.setVisibility(View.INVISIBLE)).start(), 9000);
                });
            });
        }
    };

    // ------------------------------------------------------------ piezas

    private int px(float dp) {
        return Math.round(dp * d);
    }

    private GradientDrawable redondeado(int color, float radioDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radioDp));
        return g;
    }

    /** Tarjeta de «cristal oscuro». */
    private LinearLayout tarjeta() {
        LinearLayout t = columna();
        GradientDrawable g = redondeado(0x99101018, 16);
        g.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        t.setBackground(g);
        t.setPadding(px(14), px(10), px(14), px(10));
        return t;
    }

    private LinearLayout columna() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView texto(String s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setShadowLayer(4, 0, 0, 0x88000000);
        return t;
    }

    private TextView etiqueta(String s) {
        TextView t = texto(s, 10, 0xFFC77DFF);
        t.setLetterSpacing(0.15f);
        t.setPadding(0, 0, 0, px(4));
        return t;
    }

    private LinearLayout.LayoutParams conMargen() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.bottomMargin = px(10);
        return lp;
    }

    private FrameLayout.LayoutParams posicion(int gravedad, int margen, int ancho) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ancho, -2, gravedad);
        lp.setMargins(margen, margen, margen, margen);
        return lp;
    }

    private static void ocultar(View... vs) {
        for (View v : vs) v.setVisibility(View.GONE);
    }

    private static void mostrar(View v) {
        if (v.getVisibility() == View.VISIBLE) return;
        v.setAlpha(0f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).setDuration(800).start();
    }

    // ------------------------------------------------------------ red

    private static byte[] bajar(String url, int maximo) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "NAVI-TV/0.7 (+https://github.com/NexusONtheline/navi-tv)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            if (c.getContentLength() > maximo) throw new IOException("muy grande");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    if (out.size() > maximo) throw new IOException("muy grande");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String postJson(String url, String cuerpo) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        try {
            try (OutputStream o = c.getOutputStream()) {
                o.write(cuerpo.getBytes("UTF-8"));
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
