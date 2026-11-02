package io.github.nexusontheline.navitv;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.voice.VoiceInteractionSession;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * La carita de NAVI encima de lo que estés viendo. Dos modos:
 * - La llamas con el micro: aparece en la esquina, te escucha, responde y se va.
 * - Aparece sola (saludo o porque algo le llamó la atención): mira la pantalla en
 *   silencio, y si decide comentar entra flotando, habla y se va. No roba el control.
 */
public class NaviSesion extends VoiceInteractionSession {
    private static final String TAG = "NAVI-Sesion";
    static final ComponentName RECONOCEDOR_GOOGLE = new ComponentName("com.google.android.katniss",
            "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService");

    static final String MODO = "modo";
    static final String MIRAR = "mirar";
    static final String MIRAR_YA = "mirar_ya";  // pruebas: comenta sí o sí
    static final String SALUDO = "saludo";

    /** Para el reloj interno (NaviAsistente): no aparecer encima de sí misma ni muy seguido. */
    static volatile boolean visible;
    static volatile long ultimaAparicion;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService hilo = Executors.newSingleThreadExecutor();
    private final Random azar = new Random();
    private final Runnable irse = this::hide;
    private final Runnable sinCaptura = () -> { if (esperandoCaptura) observarAhora(); };

    private float d;
    private int anchoPantalla, altoPantalla;
    private LinearLayout actor;
    private Carita carita;
    private TextView objeto;
    private ValueAnimator paseo, vaiven;

    private SpeechRecognizer oido;
    private TextToSpeech voz;
    private boolean vozLista;
    private AudioFocusRequest foco;
    private android.media.ToneGenerator tono;

    private String modo = "";
    private boolean pensando;
    private boolean esperandoCaptura;
    private Bitmap captura;
    private String appActual;
    private int reintentos;
    /** Sube cada vez que se abre o cierra; así se ignoran respuestas de una vez anterior. */
    private int vez;

    public NaviSesion(Context ctx) {
        super(ctx);
    }

    private boolean sola() {
        return MIRAR.equals(modo) || MIRAR_YA.equals(modo) || SALUDO.equals(modo);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        voz = new TextToSpeech(getContext(), ok -> {
            if (ok != TextToSpeech.SUCCESS) return;
            Voz.configurar(voz, getContext());
            voz.setAudioAttributes(atributos());
            voz.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { ui.post(() -> carita.setEstado(Carita.Estado.HABLANDO)); }
                @Override public void onDone(String id) { ui.post(NaviSesion.this::terminoDeHablar); }
                @Override public void onError(String id) { ui.post(NaviSesion.this::terminoDeHablar); }
            });
            vozLista = true;
        });
        oido = crearOido();  // listo de antemano: cada milisegundo cuenta al apretar el micro
    }

    @Override
    public View onCreateContentView() {
        Context ctx = getContext();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        d = dm.density;
        anchoPantalla = Math.max(dm.widthPixels, dm.heightPixels);
        altoPantalla = Math.min(dm.widthPixels, dm.heightPixels);

        Window w = getWindow().getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        FrameLayout raiz = new FrameLayout(ctx);
        raiz.setBackgroundColor(Color.TRANSPARENT);
        raiz.setClipChildren(false);

        actor = new LinearLayout(ctx);
        actor.setOrientation(LinearLayout.HORIZONTAL);
        actor.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        actor.setClipChildren(false);

        objeto = new TextView(ctx);
        objeto.setTextSize(38);
        objeto.setGravity(Gravity.CENTER);
        objeto.setShadowLayer(12, 0, 0, 0xAA000000);
        objeto.setVisibility(View.GONE);
        actor.addView(objeto, new LinearLayout.LayoutParams(px(60), px(70)));

        carita = new Carita(ctx);
        actor.addView(carita, new LinearLayout.LayoutParams(px(110), px(95)));

        raiz.addView(actor, new FrameLayout.LayoutParams(px(170), px(100), Gravity.TOP | Gravity.START));
        ponerEnCasa();
        return raiz;
    }

    private int px(float dp) {
        return Math.round(dp * d);
    }

    /** La esquina de abajo a la derecha, donde vive cuando la llamas. */
    private void ponerEnCasa() {
        actor.setTranslationX(anchoPantalla - px(170) - px(36));
        actor.setTranslationY(altoPantalla - px(100) - px(24));
    }

    // ------------------------------------------------------------ abrir / cerrar

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        visible = true;
        vez++;
        pensando = false;
        reintentos = 0;
        captura = null;
        appActual = null;
        ui.removeCallbacksAndMessages(null);
        pararAnimaciones();
        if (voz != null) voz.stop();
        modo = args != null ? args.getString(MODO, "") : "";

        Window w = getWindow().getWindow();
        if (sola()) {
            // Mira en silencio sin quitarte el control
            if (w != null) w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            actor.setVisibility(View.INVISIBLE);
            esperandoCaptura = true;
            ui.postDelayed(sinCaptura, 3500);
            return;
        }

        // La llamaste con el micro
        if (w != null) w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        esperandoCaptura = false;
        actor.setVisibility(View.VISIBLE);
        objeto.setVisibility(View.GONE);
        carita.setReaccion(Carita.Reaccion.NORMAL);
        ponerEnCasa();
        if (Conf.cargar(getContext()).geminiKey.isEmpty()) {
            decirYIrse("Me falta la clave de Gemini. Ponla en la app NAVI, porfa.");
            return;
        }
        escuchar();
    }

    @Override
    public void onHandleAssist(AssistState estado) {
        try {
            AssistStructure s = estado.getAssistStructure();
            if (s == null || (appActual != null && !estado.isFocused())) return;
            ComponentName c = s.getActivityComponent();
            if (c == null) return;
            PackageManager pm = getContext().getPackageManager();
            appActual = pm.getApplicationLabel(pm.getApplicationInfo(c.getPackageName(), 0)).toString();
            Log.i(TAG, "app en pantalla: " + appActual);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onHandleScreenshot(Bitmap pantalla) {
        Log.i(TAG, "captura: " + (pantalla == null ? "ninguna" : pantalla.getWidth() + "x" + pantalla.getHeight()));
        captura = pantalla;
        if (esperandoCaptura) observarAhora();
    }

    @Override
    public void onHide() {
        visible = false;
        vez++;
        pensando = false;
        esperandoCaptura = false;
        ui.removeCallbacksAndMessages(null);
        pararAnimaciones();
        if (oido != null) oido.cancel();
        if (voz != null) voz.stop();
        soltarFoco();
        super.onHide();
    }

    @Override
    public void onDestroy() {
        if (oido != null) oido.destroy();
        if (voz != null) voz.shutdown();
        if (tono != null) tono.release();
        hilo.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!sola() && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (!pensando) {  // OK del control: volver a hablarle
                reintentos = 0;
                escuchar();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ aparecer sola

    private void observarAhora() {
        esperandoCaptura = false;
        ui.removeCallbacks(sinCaptura);
        final int estaVez = vez;
        final Bitmap pantalla = captura;
        final String app = appActual;
        final boolean saludo = SALUDO.equals(modo);
        final boolean forzar = MIRAR_YA.equals(modo);
        hilo.execute(() -> {
            Cerebro.Observacion o = null;
            try {
                o = Cerebro.de(getContext()).observar(pantalla, app, saludo, forzar);
            } catch (Cerebro.Fallo f) {
                Log.w(TAG, "no pude mirar: " + f.getMessage());
            }
            final Cerebro.Observacion obs = o;
            ui.post(() -> {
                if (estaVez != vez) return;
                if (obs == null || !obs.comentar) {
                    hide();
                    return;
                }
                entrar(obs);
            });
        });
    }

    /** Entra flotando desde un borde, con su cara y su objeto, y habla. */
    private void entrar(Cerebro.Observacion o) {
        ultimaAparicion = SystemClock.elapsedRealtime();
        carita.setReaccion(Carita.Reaccion.de(o.reaccion));
        carita.setEstado(Carita.Estado.REPOSO);

        boolean hayObjeto = !o.objeto.isEmpty();
        objeto.setText(o.objeto);
        objeto.setVisibility(hayObjeto ? View.VISIBLE : View.INVISIBLE);

        // Un sitio cerca de los bordes, para no tapar el centro de lo que ves
        int margen = px(40);
        float destinoX = azar.nextBoolean() ? anchoPantalla - px(170) - margen : margen;
        float destinoY = azar.nextBoolean() ? altoPantalla - px(100) - px(30) : px(90);
        boolean desdeDerecha = destinoX > anchoPantalla / 2f;
        actor.setTranslationX(desdeDerecha ? anchoPantalla + px(20) : -px(190));
        actor.setTranslationY(destinoY);
        actor.setVisibility(View.VISIBLE);

        actor.animate().translationX(destinoX).setDuration(1100)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .withEndAction(() -> {
                    pasear(destinoX, destinoY);
                    decirYIrse(o.frase);
                }).start();

        if (hayObjeto) {  // el objeto aparece con un saltito y flota
            objeto.setScaleX(0f);
            objeto.setScaleY(0f);
            objeto.animate().scaleX(1f).scaleY(1f).setStartDelay(900).setDuration(500)
                    .setInterpolator(new OvershootInterpolator(2.5f)).start();
            vaiven = ObjectAnimator.ofFloat(objeto, View.TRANSLATION_Y, -px(6), px(6));
            vaiven.setDuration(900);
            vaiven.setRepeatMode(ValueAnimator.REVERSE);
            vaiven.setRepeatCount(ValueAnimator.INFINITE);
            vaiven.start();
        }
    }

    /** Mientras habla, se pasea un poquito. */
    private void pasear(float x, float y) {
        float hacia = x > anchoPantalla / 2f ? -px(140) : px(140);
        paseo = ValueAnimator.ofFloat(0f, 1f);
        paseo.setDuration(5000);
        paseo.setRepeatMode(ValueAnimator.REVERSE);
        paseo.setRepeatCount(ValueAnimator.INFINITE);
        paseo.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        paseo.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            actor.setTranslationX(x + hacia * f);
            actor.setTranslationY(y - px(14) * (float) Math.sin(f * Math.PI));
        });
        paseo.start();
    }

    /** Se va por el borde más cercano y se cierra. */
    private void salir() {
        pararAnimaciones();
        boolean derecha = actor.getTranslationX() > anchoPantalla / 2f;
        actor.animate().translationX(derecha ? anchoPantalla + px(20) : -px(190))
                .setDuration(900).setInterpolator(new AccelerateInterpolator())
                .withEndAction(this::hide).start();
    }

    private void pararAnimaciones() {
        if (paseo != null) paseo.cancel();
        if (vaiven != null) vaiven.cancel();
        paseo = null;
        vaiven = null;
        if (actor != null) actor.animate().cancel();
        if (objeto != null) {
            objeto.animate().cancel();
            objeto.setTranslationY(0);
        }
    }

    // ------------------------------------------------------------ oído

    private void escuchar() {
        if (voz != null) voz.stop();
        ui.removeCallbacks(irse);
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            decirYIrse("No tengo permiso para usar el micrófono. Abre la app NAVI una vez y acéptalo.");
            return;
        }
        carita.setEstado(Carita.Estado.REPOSO);  // se pone rosa cuando ya escucha de verdad
        if (oido == null) oido = crearOido();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-EC");
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        oido.startListening(i);
    }

    private SpeechRecognizer crearOido() {
        SpeechRecognizer s = SpeechRecognizer.createSpeechRecognizer(getContext(), RECONOCEDOR_GOOGLE);
        s.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) {
                Log.i(TAG, "escuchando");
                if (!visible || sola()) return;
                carita.setEstado(Carita.Estado.ESCUCHANDO);
                bip();
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { if (!sola()) carita.setEstado(Carita.Estado.PENSANDO); }
            @Override public void onEvent(int t, Bundle b) {}
            @Override public void onPartialResults(Bundle b) {}

            @Override public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String t = r == null || r.isEmpty() ? null : r.get(0);
                Log.i(TAG, "oí: " + t);
                if (!visible || sola()) return;
                if (t == null || t.trim().isEmpty()) noTeEscuche();
                else pensar(t);
            }

            @Override public void onError(int error) {
                Log.w(TAG, "error del oído: " + error);
                if (!visible || sola()) return;
                switch (error) {
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        decirYIrse("No tengo permiso para usar el micrófono.");
                        break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_SERVER:
                        decirYIrse("Uy, no tengo internet ahora mismo.");
                        break;
                    case SpeechRecognizer.ERROR_CLIENT:
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        // El oído de Google aún no soltó la vez anterior: probar otra vez
                        if (reintentos++ < 2) {
                            if (oido != null) oido.destroy();
                            oido = null;
                            ui.postDelayed(NaviSesion.this::escuchar, 120);
                        } else {
                            noTeEscuche();
                        }
                        break;
                    default:  // no habló nadie o no se entendió
                        noTeEscuche();
                }
            }
        });
        return s;
    }

    /** Un bip suave: «ya te escucho, habla». */
    private void bip() {
        try {
            if (tono == null) tono = new android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 35);
            tono.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (RuntimeException e) {
            Log.w(TAG, "sin bip", e);
        }
    }

    /** Sin globo de texto: la carita se queda quieta un momento y se va. */
    private void noTeEscuche() {
        carita.setEstado(Carita.Estado.REPOSO);
        ui.removeCallbacks(irse);
        ui.postDelayed(irse, 1800);
    }

    // ------------------------------------------------------------ cerebro y voz

    private void pensar(String texto) {
        pensando = true;
        carita.setEstado(Carita.Estado.PENSANDO);
        final int estaVez = vez;
        final Bitmap pantalla = captura;
        final String app = appActual;
        hilo.execute(() -> {
            String respuesta;
            try {
                respuesta = Cerebro.de(getContext()).responder(texto, pantalla, app, null);
            } catch (Cerebro.Fallo f) {
                respuesta = f.getMessage();
            }
            final String r = respuesta;
            ui.post(() -> {
                if (estaVez != vez) return;  // la cerraste mientras pensaba
                pensando = false;
                decirYIrse(r);
            });
        });
    }

    private void decirYIrse(String texto) {
        if (!visible) return;
        if (vozLista) {
            pedirFoco();
            voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "navi");
        } else {
            carita.setEstado(Carita.Estado.REPOSO);
            ui.postDelayed(sola() ? this::salir : irse, 3000);
        }
    }

    private void terminoDeHablar() {
        soltarFoco();
        carita.setEstado(Carita.Estado.REPOSO);
        ui.removeCallbacks(irse);
        if (sola()) ui.postDelayed(this::salir, 1500);
        else ui.postDelayed(irse, 2500);  // un momento por si quieres seguir hablando (OK)
    }

    // ------------------------------------------------------------ bajar el volumen mientras habla

    private static AudioAttributes atributos() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    private void pedirFoco() {
        if (Build.VERSION.SDK_INT < 26) return;
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        foco = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(atributos()).build();
        am.requestAudioFocus(foco);
    }

    private void soltarFoco() {
        if (foco == null || Build.VERSION.SDK_INT < 26) return;
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        am.abandonAudioFocusRequest(foco);
        foco = null;
    }
}
