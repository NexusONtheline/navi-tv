package io.github.nexusontheline.navitv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** NAVI en la tele: le hablas con el micro del control (o le escribes) y te responde con voz. */
public class Navi extends Activity {
    private static final int VOZ = 1;
    private static final int FONDO = 0xFF0B0A12;
    private static final int MORADO = 0xFFC77DFF;
    private static final int GRIS = 0xFF8A7FA8;

    private static Cerebro cerebro;  // sobrevive a girar/recrear la pantalla: no olvida la charla

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService hilo = Executors.newSingleThreadExecutor();

    private TextView estado;
    private LinearLayout charla;
    private ScrollView desplazar;
    private LinearLayout filaEscribir;
    private EditText campo;
    private Button hablar;
    private TextToSpeech voz;
    private boolean vozLista;
    private boolean pensando;
    private float d;

    @Override
    protected void onCreate(Bundle guardado) {
        super.onCreate(guardado);
        d = getResources().getDisplayMetrics().density;
        if (cerebro == null) cerebro = new Cerebro(this);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(FONDO);
        raiz.setPadding(px(56), px(32), px(56), px(28));

        TextView titulo = texto("NAVI", 34, MORADO);
        titulo.setLetterSpacing(0.2f);
        raiz.addView(titulo);
        estado = texto("En la actualidad. En este momento.", 15, GRIS);
        raiz.addView(estado);

        charla = new LinearLayout(this);
        charla.setOrientation(LinearLayout.VERTICAL);
        charla.setPadding(0, px(12), 0, px(12));
        desplazar = new ScrollView(this);
        desplazar.setFocusable(false);
        desplazar.addView(charla);
        raiz.addView(desplazar, new LinearLayout.LayoutParams(-1, 0, 1f));

        filaEscribir = new LinearLayout(this);
        filaEscribir.setOrientation(LinearLayout.HORIZONTAL);
        filaEscribir.setVisibility(View.GONE);
        campo = new EditText(this);
        campo.setSingleLine(true);
        campo.setTextColor(Color.WHITE);
        campo.setHintTextColor(GRIS);
        campo.setHint("Escríbele a NAVI…");
        campo.setTextSize(18);
        campo.setBackground(fondoEnfocable());
        campo.setPadding(px(14), px(10), px(14), px(10));
        campo.setImeOptions(EditorInfo.IME_ACTION_SEND);
        campo.setOnEditorActionListener((v, accion, ev) -> {
            enviarEscrito();
            return true;
        });
        filaEscribir.addView(campo, new LinearLayout.LayoutParams(0, -2, 1f));
        Button enviar = boton("Enviar");
        enviar.setOnClickListener(v -> enviarEscrito());
        filaEscribir.addView(enviar);
        raiz.addView(filaEscribir);

        LinearLayout botones = new LinearLayout(this);
        botones.setOrientation(LinearLayout.HORIZONTAL);
        botones.setPadding(0, px(12), 0, 0);
        hablar = boton("🎙  Hablar");
        hablar.setOnClickListener(v -> escuchar());
        Button escribir = boton("⌨  Escribir");
        escribir.setOnClickListener(v -> mostrarEscribir());
        Button nueva = boton("Nueva charla");
        nueva.setOnClickListener(v -> {
            cerebro.olvidar();
            charla.removeAllViews();
            estado.setText("Cierra el mundo, abre el siguiente.");
        });
        Button ajustes = boton("Salvapantallas");
        ajustes.setOnClickListener(v -> startActivity(new Intent(this, Ajustes.class)));
        botones.addView(hablar);
        botones.addView(escribir);
        botones.addView(nueva);
        botones.addView(ajustes);
        raiz.addView(botones);

        setContentView(raiz);
        hablar.requestFocus();

        voz = new TextToSpeech(this, ok -> {
            if (ok != TextToSpeech.SUCCESS) return;
            voz.setLanguage(new Locale("es", "ES"));
            voz.setPitch(1.15f);
            voz.setSpeechRate(1.0f);
            voz.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { ui.post(() -> estado.setText("hablando…")); }
                @Override public void onDone(String id) { ui.post(() -> estado.setText("")); }
                @Override public void onError(String id) { ui.post(() -> estado.setText("")); }
            });
            vozLista = true;
        });

        if (Conf.cargar(this).geminiKey.isEmpty()) {
            burbuja("Me falta la clave de Gemini para conectarme al Nexo. "
                    + "Pásamela desde la laptop o en Salvapantallas → Clave de Gemini.", false);
        } else if (guardado == null) {
            ui.postDelayed(this::escuchar, 400);  // al abrir NAVI, escucha directo
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!pensando) escuchar();  // la abren otra vez (botón del control): a escuchar
    }

    @Override
    public boolean onSearchRequested() {
        escuchar();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            escuchar();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (voz != null) voz.shutdown();
        hilo.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------ conversación

    private void escuchar() {
        if (pensando) return;
        if (voz != null) voz.stop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-EC");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla con NAVI");
        // El de Google TV, que usa el micrófono del control (si no, Android pregunta cuál usar)
        Intent tv = new Intent(i).setPackage("com.google.android.katniss");
        if (tv.resolveActivity(getPackageManager()) != null) i = tv;
        try {
            estado.setText("escuchando…");
            startActivityForResult(i, VOZ);
        } catch (ActivityNotFoundException e) {
            estado.setText("No encuentro el micrófono. Escríbeme.");
            mostrarEscribir();
        }
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent datos) {
        super.onActivityResult(pedido, resultado, datos);
        if (pedido != VOZ) return;
        estado.setText("");
        if (resultado != RESULT_OK || datos == null) return;
        ArrayList<String> frases = datos.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (frases != null && !frases.isEmpty()) enviar(frases.get(0));
    }

    private void mostrarEscribir() {
        filaEscribir.setVisibility(View.VISIBLE);
        campo.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.showSoftInput(campo, InputMethodManager.SHOW_IMPLICIT);
    }

    private void enviarEscrito() {
        String t = campo.getText().toString().trim();
        if (t.isEmpty()) return;
        campo.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(campo.getWindowToken(), 0);
        filaEscribir.setVisibility(View.GONE);
        hablar.requestFocus();
        enviar(t);
    }

    private void enviar(String texto) {
        if (pensando) return;
        pensando = true;
        burbuja(texto, true);
        estado.setText("conectando al Nexo…");
        hilo.execute(() -> {
            String respuesta;
            try {
                respuesta = cerebro.responder(texto, nombre ->
                        ui.post(() -> estado.setText("usando " + nombre.replace('_', ' ') + "…")));
            } catch (Cerebro.Fallo f) {
                respuesta = f.getMessage();
            }
            final String r = respuesta;
            ui.post(() -> {
                pensando = false;
                estado.setText("");
                burbuja(r, false);
                decir(r);
                hablar.requestFocus();
            });
        });
    }

    private void decir(String texto) {
        if (!vozLista) return;
        voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "navi");
    }

    // ------------------------------------------------------------ interfaz

    private void burbuja(String texto, boolean delUsuario) {
        TextView t = texto(texto, 19, delUsuario ? 0xFFDDD6EE : Color.WHITE);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(px(16), px(10), px(16), px(10));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setCornerRadius(px(10));
        fondo.setColor(delUsuario ? 0xFF1E1B2B : 0xFF231A36);
        if (!delUsuario) fondo.setStroke(px(1), 0x66C77DFF);
        t.setBackground(fondo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(10);
        lp.gravity = delUsuario ? Gravity.END : Gravity.START;
        if (delUsuario) lp.leftMargin = px(160); else lp.rightMargin = px(160);
        charla.addView(t, lp);
        desplazar.post(() -> desplazar.fullScroll(View.FOCUS_DOWN));
    }

    private int px(int dp) {
        return (int) (dp * d);
    }

    private TextView texto(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private StateListDrawable fondoEnfocable() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0xFF1A1726);
        normal.setCornerRadius(px(8));
        GradientDrawable foco = new GradientDrawable();
        foco.setColor(0xFF2A2340);
        foco.setCornerRadius(px(8));
        foco.setStroke(px(2), MORADO);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, foco);
        s.addState(new int[]{}, normal);
        return s;
    }

    private Button boton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setBackground(fondoEnfocable());
        b.setPadding(px(24), px(10), px(24), px(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(14);
        lp.leftMargin = px(0);
        b.setLayoutParams(lp);
        return b;
    }
}
