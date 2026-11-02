package io.github.nexusontheline.navitv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * La carita de NAVI: el mismo PC beige de los 90 que la mascota de la laptop,
 * con los cables del Nexo bajando del techo. Se dibuja en un lienzo de
 * 230×200 unidades y se escala al tamaño de la vista.
 */
public class Carita extends View {
    public enum Estado { REPOSO, ESCUCHANDO, PENSANDO, HABLANDO }

    private static final float ANCHO = 230, ALTO = 200;
    private static final int BEIGE = rgb(0.80f, 0.76f, 0.66f);
    private static final int BEIGE_SOMBRA = rgb(0.58f, 0.54f, 0.46f);
    private static final int BEIGE_LUZ = rgb(0.88f, 0.85f, 0.77f);
    private static final int CABLE = rgb(0.34f, 0.34f, 0.41f);
    private static final int AZUL = rgb(0.62f, 0.80f, 1.0f);
    private static final int ROSA = rgb(0.88f, 0.27f, 0.48f);
    private static final int VERDE = rgb(0.25f, 0.65f, 0.35f);

    // Zonas del dibujo: x, y, ancho, alto
    private static final float[] MONITOR = {20, 34, 124, 100};
    private static final float[] PANTALLA = {32, 45, 100, 72};
    private static final float[] TORRE = {158, 64, 56, 112};
    private static final float OJOS_Y = 76;
    private static final float[] OJOS_X = {68, 96};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path camino = new Path();
    private final RectF r = new RectF();
    private final long inicio = SystemClock.uptimeMillis();
    private long proximoParpadeo = inicio + 2500;
    private long parpadeo = 0;
    private Estado estado = Estado.REPOSO;

    public Carita(Context ctx) {
        super(ctx);
    }

    public void setEstado(Estado e) {
        estado = e;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float t = (SystemClock.uptimeMillis() - inicio) / 1000f;
        float escala = Math.min(getWidth() / ANCHO, getHeight() / ALTO);
        c.save();
        c.scale(escala, escala);

        cablesDeArriba(c, t);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(90, 0, 0, 0));  // sombra en la "mesa"
        r.set(8, ALTO - 14, ANCHO - 8, ALTO - 4);
        c.drawOval(r, p);
        torre(c, t);
        cable(c, TORRE[0] + 6, TORRE[1] + 30, MONITOR[0] + MONITOR[2] - 4, 92, t, 8, 2.2f, 0);
        monitor(c, t);

        c.restore();
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------ piezas

    private void cable(Canvas c, float x1, float y1, float x2, float y2, float t,
                       float caida, float grosor, float fase) {
        float vaiven = (float) Math.sin(t * 1.2 + fase) * 3;
        camino.reset();
        camino.moveTo(x1, y1);
        camino.cubicTo(x1 + (x2 - x1) * 0.3f + vaiven, y1 + caida,
                x1 + (x2 - x1) * 0.7f - vaiven, y2 + caida, x2, y2);
        trazo(CABLE, grosor);
        c.drawPath(camino, p);
    }

    private void cablesDeArriba(Canvas c, float t) {
        float[][] cables = {{44, 2.4f, 0}, {88, 1.6f, 1.3f}, {118, 3f, 2.1f}, {176, 2f, 0.7f}, {204, 1.4f, 2.8f}};
        for (int i = 0; i < cables.length; i++) {
            float x = cables[i][0];
            float vaiven = (float) Math.sin(t * 0.9 + cables[i][2]) * 5;
            float finX = 70 + i * 30;
            camino.reset();
            camino.moveTo(x, 0);
            camino.cubicTo(x + vaiven, 30, finX - vaiven, 20, finX, MONITOR[1] + 20);
            trazo(CABLE, cables[i][1]);
            c.drawPath(camino, p);
        }
    }

    private void torre(Canvas c, float t) {
        float x = TORRE[0], y = TORRE[1], w = TORRE[2], h = TORRE[3];
        r.set(x, y, x + w, y + h);
        relleno(BEIGE);
        c.drawRoundRect(r, 4, 4, p);
        trazo(BEIGE_SOMBRA, 1.5f);
        c.drawRoundRect(r, 4, 4, p);
        for (int i = 0; i < 2; i++) {  // bahías
            r.set(x + 7, y + 10 + i * 13, x + w - 7, y + 19 + i * 13);
            relleno(BEIGE_LUZ);
            c.drawRect(r, p);
            trazo(BEIGE_SOMBRA, 1);
            c.drawRect(r, p);
        }
        relleno(rgb(0.2f, 0.2f, 0.22f));  // disquetera
        c.drawRect(x + 12, y + 44, x + w - 12, y + 47, p);
        relleno(VERDE);
        c.drawCircle(x + w - 12, y + 62, 2, p);
        boolean disco = estado == Estado.PENSANDO && ((int) (t * 9)) % 2 == 0;
        relleno(disco ? rgb(1f, 0.7f, 0.2f) : rgb(0.35f, 0.3f, 0.2f));
        c.drawCircle(x + w - 20, y + 62, 2, p);
    }

    private void monitor(Canvas c, float t) {
        float x = MONITOR[0], y = MONITOR[1], w = MONITOR[2], h = MONITOR[3];
        camino.reset();  // peana
        camino.moveTo(x + 40, y + h);
        camino.lineTo(x + w - 40, y + h);
        camino.lineTo(x + w - 30, y + h + 12);
        camino.lineTo(x + 30, y + h + 12);
        camino.close();
        relleno(BEIGE_SOMBRA);
        c.drawPath(camino, p);

        r.set(x, y, x + w, y + h);  // carcasa
        relleno(BEIGE);
        c.drawRoundRect(r, 9, 9, p);
        trazo(BEIGE_SOMBRA, 1.5f);
        c.drawRoundRect(r, 9, 9, p);
        trazo(BEIGE_LUZ, 1.2f);
        c.drawLine(x + 4, y + 3, x + w - 8, y + 3, p);
        relleno(BEIGE_SOMBRA);  // rejilla
        for (int i = 0; i < 5; i++) c.drawRect(x + 10 + i * 5, y + h - 9, x + 13 + i * 5, y + h - 5, p);
        boolean encendida = estado != Estado.REPOSO && ((int) (t * 4)) % 2 == 1;
        relleno(encendida ? ROSA : VERDE);
        c.drawCircle(x + w - 12, y + h - 7, 2, p);

        // Pantalla CRT
        float px = PANTALLA[0], py = PANTALLA[1], pw = PANTALLA[2], ph = PANTALLA[3];
        int color = estado == Estado.ESCUCHANDO ? ROSA : AZUL;
        r.set(px, py, px + pw, py + ph);
        relleno(rgb(0.02f, 0.03f, 0.05f));
        c.drawRoundRect(r, 8, 8, p);
        relleno(conAlfa(color, 0.10f + 0.03f * (float) Math.sin(t * 3)));
        c.drawRoundRect(r, 8, 8, p);

        c.save();
        c.clipRect(px + 2, py + 2, px + pw - 2, py + ph - 2);
        relleno(conAlfa(color, 0.05f));
        for (float linea = py; linea < py + ph; linea += 3) c.drawRect(px, linea, px + pw, linea + 1, p);
        if (estado == Estado.PENSANDO) {
            float franja = py + (t * 50) % ph;
            relleno(conAlfa(color, 0.18f));
            c.drawRect(px, franja, px + pw, franja + 4, p);
        }
        cara(c, t, color);
        c.restore();

        trazo(Color.argb(18, 255, 255, 255), 3);  // reflejo del cristal
        camino.reset();
        camino.moveTo(px + 8, py + 6);
        camino.cubicTo(px + 30, py + 2, px + 60, py + 2, px + pw - 12, py + 6);
        c.drawPath(camino, p);

        if (estado == Estado.ESCUCHANDO) ondas(c, t, x - 4, y + h / 2);
    }

    private void cara(Canvas c, float t, int color) {
        long ahora = SystemClock.uptimeMillis();
        if (ahora > proximoParpadeo) {
            parpadeo = ahora;
            proximoParpadeo = ahora + 2500 + (long) (Math.random() * 3500);
        }
        float avance = (ahora - parpadeo) / 1000f;
        float abierto = avance > 0.15f ? 1f : Math.abs(avance / 0.075f - 1);
        float mx = 0, my = 0;
        if (estado == Estado.PENSANDO) {
            mx = 5 * (float) Math.sin(t * 2);
            my = -4;
        } else {
            mx = 3 * (float) Math.sin(t * 0.6);  // mira un poco alrededor
        }
        float altoOjo = estado == Estado.ESCUCHANDO ? 15 : 12;
        relleno(conAlfa(color, 0.95f));
        for (float ox : OJOS_X) {
            float cx = ox + mx, cy = OJOS_Y + my;
            float a = altoOjo * Math.max(0.12f, abierto);
            r.set(cx - 5, cy - a / 2, cx + 5, cy + a / 2);
            c.drawRoundRect(r, 3, 3, p);
        }
        float bx = (OJOS_X[0] + OJOS_X[1]) / 2 + mx * 0.5f, by = OJOS_Y + 20 + my * 0.5f;
        if (estado == Estado.HABLANDO) {
            float boca = Math.abs((float) Math.sin(t * 11)) * 5 + Math.abs((float) Math.sin(t * 7)) * 2;
            float alto = 7 * Math.max(0.15f, boca / 7);
            r.set(bx - 7, by - alto, bx + 7, by + alto);
            c.drawOval(r, p);
        } else {
            float sonrisa = estado == Estado.ESCUCHANDO ? 4 : 1;
            camino.reset();
            camino.moveTo(bx - 7, by);
            camino.cubicTo(bx - 3, by + sonrisa, bx + 3, by + sonrisa, bx + 7, by);
            trazo(conAlfa(color, 0.95f), 1.8f);
            c.drawPath(camino, p);
        }
    }

    /** Ondas de sonido a la izquierda del monitor, cuando escucha. */
    private void ondas(Canvas c, float t, float x, float y) {
        for (int i = 0; i < 3; i++) {
            float fase = (t * 0.9f + i / 3f) % 1f;
            float radio = 6 + fase * 16;
            trazo(conAlfa(ROSA, 1 - fase), 2);
            r.set(x - radio, y - radio, x + radio, y + radio);
            c.drawArc(r, 140, 80, false, p);
        }
    }

    // ------------------------------------------------------------ ayudas

    private void relleno(int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
    }

    private void trazo(int color, float grosor) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(grosor);
        p.setColor(color);
    }

    private static int rgb(float r, float g, float b) {
        return Color.rgb(Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }

    private static int conAlfa(int color, float alfa) {
        return (Math.round(Math.max(0, Math.min(1, alfa)) * 255) << 24) | (color & 0xFFFFFF);
    }
}
