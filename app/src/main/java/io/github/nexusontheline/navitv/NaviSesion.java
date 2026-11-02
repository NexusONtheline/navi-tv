package io.github.nexusontheline.navitv;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lo que pasa al apretar el micro del control: la carita de NAVI aparece en una
 * esquina encima de lo que estés viendo, te escucha, responde con voz y se va.
 */
public class NaviSesion extends VoiceInteractionSession {
    private static final String TAG = "NAVI-Sesion";
    static final ComponentName RECONOCEDOR_GOOGLE = new ComponentName("com.google.android.katniss",
            "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService hilo = Executors.newSingleThreadExecutor();
    private final Runnable irse = this::hide;

    private Carita carita;
    private SpeechRecognizer oido;
    private TextToSpeech voz;
    private boolean vozLista;
    private boolean visible;
    private boolean pensando;
    private int reintentos;
    /** Cada vez que se abre o cierra la sesión sube; así se ignoran respuestas de una vez anterior. */
    private int vez;

    public NaviSesion(Context ctx) {
        super(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        voz = new TextToSpeech(getContext(), ok -> {
            if (ok != TextToSpeech.SUCCESS) return;
            Voz.configurar(voz, getContext());
            voz.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { ui.post(() -> carita.setEstado(Carita.Estado.HABLANDO)); }
                @Override public void onDone(String id) { ui.post(NaviSesion.this::terminoDeHablar); }
                @Override public void onError(String id) { ui.post(NaviSesion.this::terminoDeHablar); }
            });
            vozLista = true;
        });
    }

    @Override
    public View onCreateContentView() {
        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;

        Window w = getWindow().getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        FrameLayout raiz = new FrameLayout(ctx);
        raiz.setBackgroundColor(Color.TRANSPARENT);
        carita = new Carita(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                (int) (110 * d), (int) (95 * d), Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, (int) (36 * d), (int) (28 * d));
        raiz.addView(carita, lp);
        return raiz;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        registrarArgumentos("", args);
        visible = true;
        vez++;
        pensando = false;
        reintentos = 0;
        ui.removeCallbacks(irse);
        if (Conf.cargar(getContext()).geminiKey.isEmpty()) {
            decirYIrse("Me falta la clave de Gemini. Ponla en la app NAVI, porfa.");
            return;
        }
        escuchar();
    }

    /** Para depurar: qué le pasa la tele a NAVI al apretar el micro. */
    private void registrarArgumentos(String prefijo, Bundle b) {
        if (b == null) return;
        try {
            for (String k : b.keySet()) {
                try {
                    Object v = b.get(k);
                    Log.i(TAG, "arg " + prefijo + k + " = " + (v == null ? "null" : v.getClass().getName() + " " + v));
                    if (v instanceof Bundle) registrarArgumentos(prefijo + k + ".", (Bundle) v);
                } catch (Throwable e) {
                    Log.i(TAG, "arg " + prefijo + k + " ilegible: " + e);
                }
            }
        } catch (Throwable e) {
            Log.i(TAG, "args ilegibles: " + e);
        }
    }

    @Override
    public void onHide() {
        visible = false;
        vez++;
        pensando = false;
        ui.removeCallbacksAndMessages(null);
        if (oido != null) oido.cancel();
        if (voz != null) voz.stop();
        super.onHide();
    }

    @Override
    public void onDestroy() {
        if (oido != null) oido.destroy();
        if (voz != null) voz.shutdown();
        hilo.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!pensando) {  // OK del control: volver a hablarle
                reintentos = 0;
                escuchar();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
        carita.setEstado(Carita.Estado.ESCUCHANDO);
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
            @Override public void onReadyForSpeech(Bundle b) { Log.i(TAG, "escuchando"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { carita.setEstado(Carita.Estado.PENSANDO); }
            @Override public void onEvent(int t, Bundle b) {}
            @Override public void onPartialResults(Bundle b) {}

            @Override public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String t = r == null || r.isEmpty() ? null : r.get(0);
                Log.i(TAG, "oí: " + t);
                if (!visible) return;
                if (t == null || t.trim().isEmpty()) noTeEscuche();
                else pensar(t);
            }

            @Override public void onError(int error) {
                Log.w(TAG, "error del oído: " + error);
                if (!visible) return;
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
                            ui.postDelayed(NaviSesion.this::escuchar, 400);
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
        hilo.execute(() -> {
            String respuesta;
            try {
                respuesta = Cerebro.de(getContext()).responder(texto, null);
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
            voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "navi");
        } else {
            carita.setEstado(Carita.Estado.REPOSO);
            ui.postDelayed(irse, 3000);
        }
    }

    private void terminoDeHablar() {
        carita.setEstado(Carita.Estado.REPOSO);
        ui.removeCallbacks(irse);
        ui.postDelayed(irse, 2500);  // un momento por si quieres seguir hablando (OK)
    }
}
