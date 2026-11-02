package io.github.nexusontheline.navitv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
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
    private TextView globo;
    private SpeechRecognizer oido;
    private TextToSpeech voz;
    private boolean vozLista;
    private boolean pensando;
    private float d;

    public NaviSesion(Context ctx) {
        super(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        voz = new TextToSpeech(getContext(), ok -> {
            if (ok != TextToSpeech.SUCCESS) return;
            voz.setLanguage(new Locale("es", "ES"));
            voz.setPitch(1.15f);
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
        d = ctx.getResources().getDisplayMetrics().density;

        Window w = getWindow().getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        FrameLayout raiz = new FrameLayout(ctx);
        raiz.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout esquina = new LinearLayout(ctx);
        esquina.setOrientation(LinearLayout.VERTICAL);
        esquina.setGravity(Gravity.END);

        globo = new TextView(ctx);
        globo.setTextColor(Color.WHITE);
        globo.setTextSize(18);
        globo.setLineSpacing(0, 1.15f);
        globo.setMaxWidth(px(520));
        globo.setPadding(px(18), px(12), px(18), px(12));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(0xEE18132A);
        fondo.setCornerRadius(px(12));
        fondo.setStroke(px(1), 0x99C77DFF);
        globo.setBackground(fondo);
        globo.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpGlobo = new LinearLayout.LayoutParams(-2, -2);
        lpGlobo.bottomMargin = px(6);
        lpGlobo.rightMargin = px(40);
        esquina.addView(globo, lpGlobo);

        carita = new Carita(ctx);
        esquina.addView(carita, new LinearLayout.LayoutParams(px(207), px(180)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(px(24), px(24), px(36), px(20));
        raiz.addView(esquina, lp);
        return raiz;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        ui.removeCallbacks(irse);
        globo.setVisibility(View.GONE);
        if (Conf.cargar(getContext()).geminiKey.isEmpty()) {
            decirYIrse("Me falta la clave de Gemini. Ponla en la app NAVI.");
            return;
        }
        escuchar();
    }

    @Override
    public void onHide() {
        ui.removeCallbacks(irse);
        if (oido != null) oido.cancel();
        if (voz != null) voz.stop();
        pensando = false;
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
            if (!pensando) escuchar();  // OK del control: volver a hablarle
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ oído

    private void escuchar() {
        if (voz != null) voz.stop();
        ui.removeCallbacks(irse);
        carita.setEstado(Carita.Estado.ESCUCHANDO);
        mostrar("…");
        if (oido == null) {
            oido = crearOido();
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-EC");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        oido.cancel();
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

            @Override public void onPartialResults(Bundle b) {
                String t = primera(b);
                if (t != null && !t.isEmpty()) mostrar("«" + t + "»");
            }

            @Override public void onResults(Bundle b) {
                String t = primera(b);
                if (t == null || t.trim().isEmpty()) noTeEscuche();
                else pensar(t);
            }

            @Override public void onError(int error) {
                Log.w(TAG, "error del oído: " + error);
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    decirYIrse("No tengo permiso para usar el micrófono.");
                } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    decirYIrse("No puedo conectar con el Nexo ahora mismo.");
                } else {
                    noTeEscuche();
                }
            }
        });
        return s;
    }

    private static String primera(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    private void noTeEscuche() {
        carita.setEstado(Carita.Estado.REPOSO);
        mostrar("No te escuché. Aprieta OK para intentarlo otra vez.");
        ui.postDelayed(irse, 5000);
    }

    // ------------------------------------------------------------ cerebro y voz

    private void pensar(String texto) {
        pensando = true;
        carita.setEstado(Carita.Estado.PENSANDO);
        mostrar("«" + texto + "»");
        hilo.execute(() -> {
            String respuesta;
            try {
                respuesta = Cerebro.de(getContext()).responder(texto, null);
            } catch (Cerebro.Fallo f) {
                respuesta = f.getMessage();
            }
            final String r = respuesta;
            ui.post(() -> {
                pensando = false;
                decirYIrse(r);
            });
        });
    }

    private void decirYIrse(String texto) {
        mostrar(texto);
        if (vozLista) {
            voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "navi");
        } else {
            carita.setEstado(Carita.Estado.REPOSO);
            ui.postDelayed(irse, 4000 + texto.length() * 60L);
        }
    }

    private void terminoDeHablar() {
        carita.setEstado(Carita.Estado.REPOSO);
        ui.removeCallbacks(irse);
        ui.postDelayed(irse, 4000 + globo.getText().length() * 25L);
    }

    private void mostrar(String texto) {
        globo.setText(texto);
        globo.setVisibility(View.VISIBLE);
    }

    private int px(int dp) {
        return (int) (dp * d);
    }
}
