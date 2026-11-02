package io.github.nexusontheline.navitv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Pantalla de ajustes, pensada para manejarse con el control remoto. */
public class Ajustes extends Activity {
    private static final int FONDO = 0xFF0B0A12;
    private static final int MORADO = 0xFFC77DFF;
    private static final int GRIS = 0xFF8A7FA8;

    private EditText etiquetas, nunca, segundos, clave;
    private CheckBox horizontales, reloj, proactiva, widgets, letras;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        recibirDesdeLaptop(getIntent());
        Conf conf = Conf.cargar(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(64), px(40), px(64), px(40));

        TextView titulo = texto("NAVI · salvapantallas", 30, MORADO);
        col.addView(titulo);
        col.addView(texto("Imágenes al azar de Safebooru cuando la tele se queda quieta.", 15, GRIS));
        espacio(col, 20);

        col.addView(texto("Etiquetas (se escriben como en safebooru.org)", 16, Color.WHITE));
        etiquetas = campo(conf.etiquetas, false);
        col.addView(etiquetas);
        col.addView(texto("Ej.: scenery   ·   serial_experiments_lain   ·   rating:general width:>=1600", 13, GRIS));
        espacio(col, 14);

        col.addView(texto("Nunca mostrar (etiquetas separadas por espacio)", 16, Color.WHITE));
        nunca = campo(conf.nuncaMostrar, false);
        col.addView(nunca);
        espacio(col, 14);

        col.addView(texto("Segundos por imagen", 16, Color.WHITE));
        segundos = campo(String.valueOf(conf.segundos), true);
        col.addView(segundos);
        espacio(col, 14);

        horizontales = casilla("Solo imágenes horizontales (llenan la pantalla)", conf.soloHorizontales);
        col.addView(horizontales);
        reloj = casilla("Mostrar la hora y la fecha", conf.mostrarReloj);
        col.addView(reloj);
        widgets = casilla("Widgets: clima, anime de hoy, ahora suena, gif, frase y NAVI durmiendo", conf.widgets);
        col.addView(widgets);
        espacio(col, 20);

        LinearLayout botones = new LinearLayout(this);
        botones.setOrientation(LinearLayout.HORIZONTAL);
        Button guardar = boton("Guardar");
        guardar.setOnClickListener(v -> {
            guardar();
            Toast.makeText(this, "Guardado ✓", Toast.LENGTH_SHORT).show();
        });
        Button ver = boton("Ver ahora");
        ver.setOnClickListener(v -> {
            guardar();
            startActivity(new Intent(this, VistaPrevia.class));
        });
        botones.addView(guardar);
        botones.addView(ver);
        col.addView(botones);

        espacio(col, 28);
        col.addView(texto("NAVI · asistente", 24, MORADO));
        col.addView(texto("Clave de Gemini " + (conf.geminiKey.isEmpty() ? "(falta)" : "(configurada ✓)"), 16, Color.WHITE));
        clave = campo("", false);
        clave.setHint(conf.geminiKey.isEmpty() ? "Pega tu clave de aistudio.google.com/apikey" : "Escribe otra solo si quieres cambiarla");
        clave.setHintTextColor(GRIS);
        clave.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(clave);
        col.addView(texto("Modelo: " + conf.modelo + (conf.ciudad.isEmpty() ? "" : "  ·  ciudad: " + conf.ciudad), 13, GRIS));
        proactiva = casilla("NAVI aparece sola: saluda al prender la tele y comenta lo que ve", conf.proactiva);
        col.addView(proactiva);
        letras = casilla("Modo letra: karaoke abajo cuando suena una canción (con traducción)", conf.letras);
        col.addView(letras);

        espacio(col, 16);
        col.addView(texto("Para ponerlo como salvapantallas: Ajustes de la tele → Sistema → Salvapantallas → «NAVI · Safebooru».", 13, GRIS));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(FONDO);
        sv.addView(col);
        setContentView(sv);
        etiquetas.requestFocus();

        // NAVI necesita el micrófono para escucharte cuando aprietas el micro del control
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    private void guardar() {
        Conf c = Conf.cargar(this);
        String et = etiquetas.getText().toString().trim();
        c.etiquetas = et.isEmpty() ? Conf.ETIQUETAS_POR_DEFECTO : et;
        c.nuncaMostrar = nunca.getText().toString();
        try {
            c.segundos = Integer.parseInt(segundos.getText().toString().trim());
        } catch (NumberFormatException e) {
            c.segundos = 60;
        }
        c.soloHorizontales = horizontales.isChecked();
        c.mostrarReloj = reloj.isChecked();
        c.widgets = widgets.isChecked();
        c.proactiva = proactiva.isChecked();
        c.letras = letras.isChecked();
        String nueva = clave.getText().toString().trim();
        if (!nueva.isEmpty()) c.geminiKey = nueva;
        c.guardar(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Si solo era probar la voz, no recargar (cortaría la frase)
        if (recibirDesdeLaptop(intent) && !intent.hasExtra("probar_voz")) recreate();
    }

    /** Desde la laptop: adb shell am start -n .../.Ajustes --es gemini_key ... --es ciudad ... */
    private boolean recibirDesdeLaptop(Intent in) {
        if (in == null || in.getExtras() == null) return false;
        Conf c = Conf.cargar(this);
        boolean algo = false;
        if (in.hasExtra("gemini_key")) { c.geminiKey = in.getStringExtra("gemini_key"); algo = true; }
        if (in.hasExtra("modelo")) { c.modelo = in.getStringExtra("modelo"); algo = true; }
        if (in.hasExtra("modelo_reserva")) { c.modeloReserva = in.getStringExtra("modelo_reserva"); algo = true; }
        if (in.hasExtra("usuario")) { c.usuario = in.getStringExtra("usuario"); algo = true; }
        if (in.hasExtra("ciudad")) { c.ciudad = in.getStringExtra("ciudad"); algo = true; }
        if (in.hasExtra("voz")) { c.voz = in.getStringExtra("voz"); algo = true; }
        if (in.hasExtra("sobre_ti")) { c.sobreTi = in.getStringExtra("sobre_ti"); algo = true; }
        if (in.hasExtra("sobre_tele")) { c.sobreTele = in.getStringExtra("sobre_tele"); algo = true; }
        if (in.hasExtra("widgets")) { c.widgets = !"false".equals(in.getStringExtra("widgets")); algo = true; }
        if (in.hasExtra("reloj")) { c.mostrarReloj = !"false".equals(in.getStringExtra("reloj")); algo = true; }
        if (in.hasExtra("tono")) {
            try { c.tono = Float.parseFloat(in.getStringExtra("tono")); algo = true; } catch (Exception ignored) {}
        }
        if (algo) {
            c.guardar(this);
            Toast.makeText(this, "NAVI recibió su configuración ✓", Toast.LENGTH_LONG).show();
        }
        if (in.hasExtra("probar_voz")) probarVoz(in.getStringExtra("probar_voz"));
        return algo;
    }

    private android.speech.tts.TextToSpeech prueba;

    /** Dice una frase con la voz configurada, para elegir voz. */
    private void probarVoz(String frase) {
        if (prueba != null) prueba.shutdown();
        prueba = new android.speech.tts.TextToSpeech(this, ok -> {
            if (ok != android.speech.tts.TextToSpeech.SUCCESS) return;
            Voz.configurar(prueba, this);
            prueba.speak(frase == null || frase.isEmpty() ? "Hola, soy NAVI. ¿Te gusta cómo sueno?" : frase,
                    android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "prueba");
        });
    }

    @Override
    protected void onDestroy() {
        if (prueba != null) prueba.shutdown();
        super.onDestroy();
    }

    // --- piezas de la interfaz

    private int px(int dp) {
        return (int) (dp * d);
    }

    private void espacio(LinearLayout col, int dp) {
        View v = new View(this);
        col.addView(v, new LinearLayout.LayoutParams(1, px(dp)));
    }

    private TextView texto(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Fondo que se ilumina en morado cuando el control está encima. */
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

    private EditText campo(String valor, boolean numero) {
        EditText e = new EditText(this);
        e.setText(valor);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setTextSize(18);
        e.setBackground(fondoEnfocable());
        e.setPadding(px(14), px(10), px(14), px(10));
        e.setInputType(numero ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private CheckBox casilla(String s, boolean marcada) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextSize(16);
        c.setTextColor(Color.WHITE);
        c.setChecked(marcada);
        c.setBackground(fondoEnfocable());
        c.setPadding(px(8), px(8), px(14), px(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(6);
        c.setLayoutParams(lp);
        return c;
    }

    private Button boton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setBackground(fondoEnfocable());
        b.setPadding(px(28), px(12), px(28), px(12));
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(16);
        b.setLayoutParams(lp);
        return b;
    }
}
