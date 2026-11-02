package io.github.nexusontheline.navitv;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * La carita de NAVI en la tele: una pantallita CRT que flota en la esquina,
 * con un brillo suave alrededor. Rosa cuando escucha, azul el resto del tiempo.
 * Se dibuja en un lienzo de 120×104 unidades y se escala al tamaño de la vista.
 */
public class Carita extends View {
    public enum Estado { REPOSO, ESCUCHANDO, PENSANDO, HABLANDO, DORMIDA }

    /** Espacio extra arriba para el gorrito de dormir. */
    private static final float ARRIBA = 30;
    private int colorGorro = 0;      // 0 = sin gorro
    private boolean gorroRayas;
    /** Para que abra un ojito un momento mientras duerme. */
    private long ojoAbiertoHasta;

    /** Cara que pone cuando aparece sola (o cuando habla). */
    public enum Reaccion { NORMAL, FELIZ, RISA, SORPRENDIDA, CURIOSA, ENAMORADA, TRISTE, PENSATIVA;
        public static Reaccion de(String s) {
            try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); } catch (Exception e) { return NORMAL; }
        }
    }

    private static final float ANCHO = 120, ALTO = 104;
    private static final int AZUL = Color.rgb(158, 204, 255);
    private static final int ROSA = Color.rgb(224, 69, 122);
    private static final int MARCO = Color.rgb(30, 27, 43);

    // La pantalla, sin contar el vaivén
    private static final float PX = 14, PY = 12, PW = 92, PH = 76;
    private static final float OJOS_Y = 44;
    private static final float[] OJOS_X = {48, 72};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path camino = new Path();
    private final RectF r = new RectF();
    private final long inicio = SystemClock.uptimeMillis();
    private long proximoParpadeo = inicio + 2500;
    private long parpadeo = 0;
    private Estado estado = Estado.REPOSO;
    private Reaccion reaccion = Reaccion.NORMAL;
    private float escalaHalo = -1;

    public Carita(Context ctx) {
        super(ctx);
        setLayerType(LAYER_TYPE_SOFTWARE, null);  // para el desenfoque del brillo
    }

    public void setReaccion(Reaccion r) {
        reaccion = r == null ? Reaccion.NORMAL : r;
        invalidate();
    }

    /** Gorrito de dormir (color 0 = quitarlo). */
    public void setGorro(int color, boolean rayas) {
        colorGorro = color;
        gorroRayas = rayas;
        invalidate();
    }

    /** Mientras duerme, abre un ojito unos segundos (para decir algo). */
    public void abrirUnOjo(long ms) {
        ojoAbiertoHasta = SystemClock.uptimeMillis() + ms;
    }

    public void setEstado(Estado e) {
        estado = e;
        invalidate();
    }

    // ------------------------------------------------------------ animaciones

    private static final long DURACION_TELE = 480;
    private long teleInicio = -1;      // animación de encender/apagar en curso
    private boolean teleEncender;
    private boolean apagada;           // terminó de apagarse: no dibujar nada
    private Runnable teleFin;
    private boolean bailando;

    /** Se enciende como una tele vieja: un puntito, una línea y la pantalla. */
    public void encender() {
        apagada = false;
        teleEncender = true;
        teleFin = null;
        teleInicio = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Se apaga como una tele vieja y luego avisa (para cerrarse). */
    public void apagar(Runnable alTerminar) {
        teleEncender = false;
        teleFin = alTerminar;
        teleInicio = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Baila al ritmo (más o menos 120 por minuto) con notitas musicales. */
    public void setBailando(boolean b) {
        bailando = b;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float t = (SystemClock.uptimeMillis() - inicio) / 1000f;
        float arriba = colorGorro != 0 ? ARRIBA : 0;
        float escala = Math.min(getWidth() / ANCHO, getHeight() / (ALTO + arriba));
        if (apagada) {
            postInvalidateOnAnimation();
            return;
        }
        c.save();
        c.scale(escala, escala);
        c.translate(0, arriba);
        float destello = 0;
        if (teleInicio >= 0) {
            float p = Math.min(1f, (SystemClock.uptimeMillis() - teleInicio) / (float) DURACION_TELE);
            float f = teleEncender ? p : 1 - p;
            float sx = f < 0.35f ? Math.max(0.02f, f / 0.35f) : 1f;
            float sy = f < 0.35f ? 0.03f : 0.03f + 0.97f * ((f - 0.35f) / 0.65f);
            c.scale(sx, sy, ANCHO / 2, PY + PH / 2);
            destello = f < 0.5f ? 1 - f * 2 : 0;
            if (p >= 1f) {
                teleInicio = -1;
                if (!teleEncender) {
                    apagada = true;
                    Runnable fin = teleFin;
                    teleFin = null;
                    if (fin != null) post(fin);
                }
            }
        }
        if (bailando) {  // rebote y balanceo al ritmo
            float beat = t * 2f;  // 2 golpes por segundo
            c.translate(0, -Math.abs((float) Math.sin(Math.PI * beat)) * 7);
            c.rotate((float) Math.sin(Math.PI * beat) * 9, ANCHO / 2, PY + PH);
        }
        boolean dormida = estado == Estado.DORMIDA;
        // flota (más lento y suave cuando duerme, como respirando)
        c.translate(0, dormida ? (float) Math.sin(t * 0.8) * 1.5f : (float) Math.sin(t * 1.6) * 2.5f);

        int color = estado == Estado.ESCUCHANDO || (reaccion == Reaccion.ENAMORADA && estado != Estado.PENSANDO)
                ? ROSA : AZUL;

        // Brillo alrededor (late más fuerte cuando escucha, muy tenue cuando duerme)
        float pulso = estado == Estado.ESCUCHANDO ? 0.55f + 0.25f * (float) Math.sin(t * 6)
                : dormida ? 0.15f + 0.05f * (float) Math.sin(t * 0.8)
                : 0.30f + 0.08f * (float) Math.sin(t * 2);
        halo.setColor(conAlfa(color, pulso));
        if (escala != escalaHalo) {
            escalaHalo = escala;
            halo.setMaskFilter(new BlurMaskFilter(10 * escala, BlurMaskFilter.Blur.NORMAL));
        }
        r.set(PX - 2, PY - 2, PX + PW + 2, PY + PH + 2);
        c.drawRoundRect(r, 16, 16, halo);

        // Marco fino y pantalla
        r.set(PX - 3, PY - 3, PX + PW + 3, PY + PH + 3);
        relleno(MARCO);
        c.drawRoundRect(r, 17, 17, p);
        r.set(PX, PY, PX + PW, PY + PH);
        relleno(Color.rgb(5, 7, 13));
        c.drawRoundRect(r, 14, 14, p);
        relleno(conAlfa(color, 0.10f + 0.03f * (float) Math.sin(t * 3)));
        c.drawRoundRect(r, 14, 14, p);

        c.save();
        c.clipRect(PX + 3, PY + 3, PX + PW - 3, PY + PH - 3);
        relleno(conAlfa(color, 0.06f));  // líneas de barrido
        for (float y = PY; y < PY + PH; y += 3) c.drawRect(PX, y, PX + PW, y + 1, p);
        if (estado == Estado.PENSANDO) {
            float franja = PY + (t * 45) % PH;
            relleno(conAlfa(color, 0.20f));
            c.drawRect(PX, franja, PX + PW, franja + 4, p);
        }
        if (dormida) dormir(c, t, color);
        else cara(c, t, color);
        c.restore();

        // Reflejo del cristal
        trazo(Color.argb(22, 255, 255, 255), 2.5f);
        camino.reset();
        camino.moveTo(PX + 12, PY + 6);
        camino.cubicTo(PX + 30, PY + 3, PX + 60, PY + 3, PX + PW - 14, PY + 6);
        c.drawPath(camino, p);

        // Lucecita bajo la pantalla
        boolean luz = estado != Estado.REPOSO && !dormida && ((int) (t * 4)) % 2 == 1;
        relleno(luz ? ROSA : dormida ? Color.rgb(40, 90, 55) : Color.rgb(64, 166, 89));
        c.drawCircle(PX + PW / 2, PY + PH + 8, 1.8f, p);

        if (colorGorro != 0) gorro(c, t);
        if (dormida) zetas(c, t, color);
        if (bailando) notitas(c, t, color);
        if (destello > 0) {  // la línea blanca de una tele vieja al encender/apagar
            relleno(conAlfa(Color.WHITE, destello));
            r.set(PX - 3, PY - 3, PX + PW + 3, PY + PH + 3);
            c.drawRoundRect(r, 17, 17, p);
        }

        c.restore();
        postInvalidateOnAnimation();
    }

    private void cara(Canvas c, float t, int color) {
        long ahora = SystemClock.uptimeMillis();
        if (ahora > proximoParpadeo) {
            parpadeo = ahora;
            proximoParpadeo = ahora + 2500 + (long) (Math.random() * 3500);
        }
        float avance = (ahora - parpadeo) / 1000f;
        float abierto = avance > 0.15f ? 1f : Math.abs(avance / 0.075f - 1);
        float mx, my = 0;
        if (estado == Estado.PENSANDO) {
            mx = 6 * (float) Math.sin(t * 2);
            my = -4;
        } else {
            mx = 3 * (float) Math.sin(t * 0.6);
        }
        Reaccion re = estado == Estado.ESCUCHANDO || estado == Estado.PENSANDO ? Reaccion.NORMAL : reaccion;
        if (re == Reaccion.PENSATIVA) { mx = 5; my = -5; }
        if (re == Reaccion.TRISTE) my = 3;
        int tinta = conAlfa(color, 0.95f);

        // Ojos
        for (int i = 0; i < OJOS_X.length; i++) {
            float cx = OJOS_X[i] + mx, cy = OJOS_Y + my;
            switch (re) {
                case FELIZ:
                case RISA:  // ^ ^
                    camino.reset();
                    camino.moveTo(cx - 6, cy + 3);
                    camino.quadTo(cx, cy - 7, cx + 6, cy + 3);
                    trazo(tinta, 2.6f);
                    c.drawPath(camino, p);
                    break;
                case SORPRENDIDA:  // ojos redondos grandes
                    relleno(tinta);
                    c.drawCircle(cx, cy, 6.5f * Math.max(0.15f, abierto), p);
                    break;
                case ENAMORADA:  // ojos de corazón
                    corazon(c, cx, cy, 6.5f + 0.8f * (float) Math.sin(t * 6), tinta);
                    break;
                case CURIOSA: {  // un ojo más grande que el otro
                    float a = (i == 0 ? 16 : 10) * Math.max(0.12f, abierto);
                    relleno(tinta);
                    r.set(cx - 5.5f, cy - a / 2, cx + 5.5f, cy + a / 2);
                    c.drawRoundRect(r, 3, 3, p);
                    break;
                }
                case TRISTE: {
                    float a = 11 * Math.max(0.12f, abierto);
                    relleno(tinta);
                    r.set(cx - 5f, cy - a / 2, cx + 5f, cy + a / 2);
                    c.drawRoundRect(r, 3, 3, p);
                    trazo(tinta, 1.6f);  // cejitas caídas
                    float lado = i == 0 ? 1 : -1;
                    c.drawLine(cx - 6 * lado, cy - 11, cx + 5 * lado, cy - 8, p);
                    break;
                }
                default: {
                    float altoOjo = estado == Estado.ESCUCHANDO ? 16 : 13;
                    float a = altoOjo * Math.max(0.12f, abierto);
                    relleno(tinta);
                    r.set(cx - 5.5f, cy - a / 2, cx + 5.5f, cy + a / 2);
                    c.drawRoundRect(r, 3, 3, p);
                }
            }
        }
        if (re == Reaccion.CURIOSA) {  // un «?» que flota
            p.setStyle(Paint.Style.FILL);
            p.setColor(conAlfa(color, 0.8f));
            p.setTextSize(14);
            p.setFakeBoldText(true);
            c.drawText("?", PX + PW - 18, PY + 20 + 2 * (float) Math.sin(t * 3), p);
            p.setFakeBoldText(false);
        }

        // Boca
        float bx = (OJOS_X[0] + OJOS_X[1]) / 2 + mx * 0.5f, by = OJOS_Y + 21 + my * 0.5f;
        if (estado == Estado.HABLANDO) {
            float boca = Math.abs((float) Math.sin(t * 11)) * 5 + Math.abs((float) Math.sin(t * 7)) * 2;
            float alto = 7 * Math.max(0.15f, boca / 7);
            relleno(tinta);
            r.set(bx - 7, by - alto, bx + 7, by + alto);
            c.drawOval(r, p);
            return;
        }
        switch (re) {
            case RISA:  // boca abierta en D
                relleno(tinta);
                r.set(bx - 8, by - 6, bx + 8, by + 8);
                c.drawArc(r, 0, 180, true, p);
                break;
            case SORPRENDIDA:
                trazo(tinta, 2f);
                c.drawCircle(bx, by + 1, 4, p);
                break;
            case TRISTE:
                camino.reset();
                camino.moveTo(bx - 7, by + 3);
                camino.cubicTo(bx - 3, by - 2, bx + 3, by - 2, bx + 7, by + 3);
                trazo(tinta, 1.9f);
                c.drawPath(camino, p);
                break;
            case PENSATIVA:
                trazo(tinta, 1.9f);
                c.drawLine(bx - 5, by + 1, bx + 5, by - 1, p);
                break;
            default: {
                float sonrisa = estado == Estado.ESCUCHANDO ? 4
                        : (re == Reaccion.FELIZ || re == Reaccion.ENAMORADA) ? 6 : 1.5f;
                camino.reset();
                camino.moveTo(bx - 7, by);
                camino.cubicTo(bx - 3, by + sonrisa, bx + 3, by + sonrisa, bx + 7, by);
                trazo(tinta, 1.9f);
                c.drawPath(camino, p);
            }
        }
    }

    /** Dormida: ojitos cerrados (o uno abierto un momento) y boquita pequeña. */
    private void dormir(Canvas c, float t, int color) {
        int tinta = conAlfa(color, 0.85f);
        boolean unOjo = SystemClock.uptimeMillis() < ojoAbiertoHasta;
        for (int i = 0; i < OJOS_X.length; i++) {
            float cx = OJOS_X[i], cy = OJOS_Y + 2;
            if (unOjo && i == 1) {
                relleno(tinta);
                r.set(cx - 5, cy - 6, cx + 5, cy + 6);
                c.drawRoundRect(r, 3, 3, p);
                continue;
            }
            camino.reset();
            camino.moveTo(cx - 6, cy - 1);
            camino.quadTo(cx, cy + 5, cx + 6, cy - 1);
            trazo(tinta, 2.2f);
            c.drawPath(camino, p);
        }
        float bx = (OJOS_X[0] + OJOS_X[1]) / 2, by = OJOS_Y + 20;
        float abre = 1.5f + 1.2f * (float) Math.sin(t * 0.8);  // respira
        relleno(conAlfa(color, 0.7f));
        r.set(bx - 3, by - abre, bx + 3, by + abre);
        c.drawOval(r, p);
    }

    /** Notitas musicales que salen bailando. */
    private void notitas(Canvas c, float t, int color) {
        p.setStyle(Paint.Style.FILL);
        String[] notas = {"♪", "♫", "♪"};
        for (int i = 0; i < 3; i++) {
            float fase = (t * 0.45f + i / 3f) % 1f;
            p.setTextSize(11 + fase * 7);
            p.setColor(conAlfa(i == 1 ? ROSA : color, (1 - fase) * 0.95f));
            float x = (i % 2 == 0 ? PX - 10 - fase * 10 : PX + PW + 2 + fase * 10);
            c.drawText(notas[i], x, PY + 30 - fase * 40, p);
        }
    }

    /** Zetas que suben flotando desde la esquina. */
    private void zetas(Canvas c, float t, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setFakeBoldText(true);
        for (int i = 0; i < 3; i++) {
            float fase = (t * 0.25f + i / 3f) % 1f;
            p.setTextSize(9 + fase * 9);
            p.setColor(conAlfa(color, (1 - fase) * 0.9f));
            c.drawText("z", PX + PW - 6 + fase * 14, PY + 10 - fase * 34, p);
        }
        p.setFakeBoldText(false);
    }

    /** Gorrito de dormir con pompón que cuelga a un lado. */
    private void gorro(Canvas c, float t) {
        float cae = (float) Math.sin(t * 0.8) * 2;
        // Cuerpo del gorro: de la banda sobre la pantalla hacia arriba y cayendo a la derecha
        camino.reset();
        camino.moveTo(PX + 4, PY + 2);
        camino.cubicTo(PX + 10, PY - 30, PX + 60, PY - 34, PX + 96 + cae, PY - 8 + cae);
        camino.cubicTo(PX + 70, PY - 16, PX + 62, PY - 6, PX + 66, PY + 2);
        camino.close();
        relleno(colorGorro);
        c.drawPath(camino, p);
        if (gorroRayas) {
            c.save();
            c.clipPath(camino);
            relleno(Color.argb(150, 255, 255, 255));
            for (int i = 0; i < 6; i++) {
                float x = PX + 6 + i * 16;
                c.drawRect(x, PY - 40, x + 6, PY + 4, p);
            }
            c.restore();
        }
        // Banda blanca y pompón
        r.set(PX - 2, PY - 4, PX + 70, PY + 5);
        relleno(Color.rgb(240, 236, 228));
        c.drawRoundRect(r, 5, 5, p);
        c.drawCircle(PX + 98 + cae, PY - 6 + cae, 6, p);
    }

    private void corazon(Canvas c, float cx, float cy, float tam, int color) {
        camino.reset();
        camino.moveTo(cx, cy + tam * 0.8f);
        camino.cubicTo(cx - tam * 1.4f, cy - tam * 0.1f, cx - tam * 0.6f, cy - tam * 1.1f, cx, cy - tam * 0.35f);
        camino.cubicTo(cx + tam * 0.6f, cy - tam * 1.1f, cx + tam * 1.4f, cy - tam * 0.1f, cx, cy + tam * 0.8f);
        relleno(color);
        c.drawPath(camino, p);
    }

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

    private static int conAlfa(int color, float alfa) {
        return (Math.round(Math.max(0, Math.min(1, alfa)) * 255) << 24) | (color & 0xFFFFFF);
    }
}
