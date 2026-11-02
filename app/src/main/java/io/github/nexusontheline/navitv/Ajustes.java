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

    private EditText etiquetas, nunca, segundos;
    private CheckBox horizontales, reloj;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
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

        espacio(col, 16);
        col.addView(texto("Para ponerlo como salvapantallas: Ajustes de la tele → Sistema → Salvapantallas → «NAVI · Safebooru».", 13, GRIS));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(FONDO);
        sv.addView(col);
        setContentView(sv);
        etiquetas.requestFocus();
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
        c.guardar(this);
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
