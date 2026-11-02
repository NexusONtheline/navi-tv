package io.github.nexusontheline.navitv;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * El pase de imágenes: pide imágenes al azar a Safebooru, las descarga
 * y las va mostrando con un fundido y un zoom lento. Lo usan tanto el
 * salvapantallas como la vista previa.
 */
public final class Pase {
    private static final String TAG = "NAVI-Pase";
    private static final String API =
            "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1&limit=100&tags=";
    private static final String AGENTE = "NAVI-TV/0.1 (+https://github.com/NexusONtheline)";
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final long FUNDIDO_MS = 1500;
    private static final long REINTENTO_MS = 30_000;

    private final Conf conf;
    private final FrameLayout raiz;
    private final ImageView[] vistas = new ImageView[2];
    private final TextView aviso;
    private final ObjectAnimator[] zooms = new ObjectAnimator[2];
    private View reloj;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> cola = new ArrayDeque<>();
    private final int ancho;
    private final int alto;

    private int actual = 0;
    private volatile boolean activo;
    private Thread hilo;

    public Pase(Context ctx) {
        conf = Conf.cargar(ctx);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        ancho = Math.max(dm.widthPixels, dm.heightPixels);
        alto = Math.min(dm.widthPixels, dm.heightPixels);

        raiz = new FrameLayout(ctx);
        raiz.setBackgroundColor(Color.BLACK);

        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(ctx);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            vistas[i] = v;
            raiz.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }

        float d = dm.density;
        int margen = (int) (32 * d);

        aviso = new TextView(ctx);
        aviso.setTextColor(0xFFC77DFF);
        aviso.setTextSize(16);
        aviso.setShadowLayer(6, 0, 0, Color.BLACK);
        aviso.setText("conectando al Nexo…");
        FrameLayout.LayoutParams lpAviso = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lpAviso.setMargins(margen, margen, margen, margen);
        raiz.addView(aviso, lpAviso);

        if (conf.mostrarReloj) {
            LinearLayout reloj = new LinearLayout(ctx);
            this.reloj = reloj;
            reloj.setOrientation(LinearLayout.VERTICAL);
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

            FrameLayout.LayoutParams lpReloj = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            lpReloj.setMargins(margen, margen, margen, margen);
            raiz.addView(reloj, lpReloj);
        }
    }

    public View vista() {
        return raiz;
    }

    public void empezar() {
        if (activo) return;
        activo = true;
        hilo = new Thread(this::bucle, "navi-pase");
        hilo.start();
    }

    public void parar() {
        activo = false;
        if (hilo != null) hilo.interrupt();
        ui.removeCallbacksAndMessages(null);
    }

    // --- en segundo plano

    private void bucle() {
        long proximo = 0;
        while (activo) {
            Bitmap imagen = null;
            try {
                imagen = siguienteImagen();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.w(TAG, "No pude traer imagen", e);
            }
            try {
                if (imagen == null) {
                    avisar("Sin conexión al Nexo. Reintento en 30 s…");
                    Thread.sleep(REINTENTO_MS);
                    continue;
                }
                long espera = proximo - SystemClock.uptimeMillis();
                if (espera > 0) Thread.sleep(espera);
            } catch (InterruptedException e) {
                return;
            }
            if (!activo) return;
            final Bitmap b = imagen;
            ui.post(() -> mostrar(b));
            proximo = SystemClock.uptimeMillis() + conf.segundos * 1000L;
        }
    }

    private Bitmap siguienteImagen() throws Exception {
        for (int intentos = 0; intentos < 5; intentos++) {
            if (cola.isEmpty()) rellenarCola();
            if (cola.isEmpty()) {
                avisar("Safebooru no encontró nada con esas etiquetas.");
                return null;
            }
            String url = cola.poll();
            try {
                Bitmap b = descargarImagen(url);
                if (b != null) return b;
            } catch (IOException e) {
                Log.w(TAG, "Falló " + url, e);
            }
            if (Thread.interrupted()) throw new InterruptedException();
        }
        return null;
    }

    private void rellenarCola() throws Exception {
        String etiquetas = (conf.etiquetas + " sort:random").trim();
        String texto = new String(descargar(API + URLEncoder.encode(etiquetas, "UTF-8")), "UTF-8").trim();
        if (texto.isEmpty() || !texto.startsWith("[")) return;

        Set<String> prohibidas = new HashSet<>();
        for (String t : conf.nuncaMostrar.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!t.isEmpty()) prohibidas.add(t);
        }

        JSONArray posts = new JSONArray(texto);
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < posts.length(); i++) {
            JSONObject p = posts.getJSONObject(i);
            int w = p.optInt("width"), h = p.optInt("height");
            if (conf.soloHorizontales && w < h * 1.2) continue;

            String archivo = p.optString("file_url", "");
            String ext = archivo.substring(archivo.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (!(ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp"))) continue;

            boolean vale = true;
            for (String t : p.optString("tags", "").toLowerCase(Locale.ROOT).split("\\s+")) {
                if (prohibidas.contains(t)) { vale = false; break; }
            }
            if (vale) urls.add(archivo);
        }
        Collections.shuffle(urls);
        cola.addAll(urls);
        Log.i(TAG, "Cola rellenada: " + urls.size() + " de " + posts.length());
    }

    private Bitmap descargarImagen(String url) throws IOException {
        byte[] datos = descargar(url);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(datos, 0, datos.length, o);
        if (o.outWidth <= 0) return null;

        int escala = 1;
        while (o.outWidth / (escala * 2) >= ancho && o.outHeight / (escala * 2) >= alto) escala *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = escala;
        return BitmapFactory.decodeByteArray(datos, 0, datos.length, o2);
    }

    private static byte[] descargar(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", AGENTE);
        try {
            int codigo = c.getResponseCode();
            if (codigo != 200) throw new IOException("HTTP " + codigo);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("Imagen demasiado grande");
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Interrumpido");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private void avisar(String texto) {
        ui.post(() -> {
            aviso.setText(texto);
            aviso.setVisibility(View.VISIBLE);
        });
    }

    // --- en pantalla

    private void mostrar(Bitmap b) {
        if (!activo) return;
        aviso.setVisibility(View.GONE);

        final ImageView vieja = vistas[actual];
        actual = 1 - actual;
        ImageView nueva = vistas[actual];

        nueva.animate().cancel();
        if (zooms[actual] != null) zooms[actual].cancel();
        nueva.setImageBitmap(b);
        nueva.setScaleX(1f);
        nueva.setScaleY(1f);
        nueva.setAlpha(0f);
        nueva.bringToFront();
        // El aviso y el reloj siempre encima de las imágenes
        aviso.bringToFront();
        if (reloj != null) reloj.bringToFront();

        nueva.animate().alpha(1f).setDuration(FUNDIDO_MS).withEndAction(() -> {
            vieja.setAlpha(0f);
            vieja.setImageDrawable(null);
        }).start();

        // Zoom muy lento mientras está en pantalla
        ObjectAnimator zoom = ObjectAnimator.ofPropertyValuesHolder(nueva,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f));
        zoom.setDuration(conf.segundos * 1000L + FUNDIDO_MS * 2);
        zoom.setInterpolator(new LinearInterpolator());
        zoom.start();
        zooms[actual] = zoom;
    }
}
