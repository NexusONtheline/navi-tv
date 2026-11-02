package io.github.nexusontheline.navitv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.nio.charset.StandardCharsets;

/**
 * Teclado invisible para escribir en la tele desde la laptop (script tv-escribir).
 * Solo está activo los segundos que dura el script; luego vuelve tu teclado normal.
 */
public class Teclado extends InputMethodService {
    public static final String ESCRIBIR = "io.github.nexusontheline.navitv.ESCRIBIR";

    private final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                Log.w("NAVI-Teclado", "No hay ningún campo de texto seleccionado");
                setResultCode(1);
                return;
            }
            if (i.getBooleanExtra("borrar", false)) {
                ic.performContextMenuAction(android.R.id.selectAll);
                ic.commitText("", 1);
            }
            String b64 = i.getStringExtra("b64");
            if (b64 != null) {
                String texto = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
                ic.commitText(texto, 1);
            }
            if (i.getBooleanExtra("enter", false)) {
                EditorInfo ei = getCurrentInputEditorInfo();
                int accion = ei != null ? ei.imeOptions & EditorInfo.IME_MASK_ACTION : EditorInfo.IME_ACTION_NONE;
                if (accion != EditorInfo.IME_ACTION_NONE && accion != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(accion);
                } else {
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
            }
            setResultCode(7);  // 7 = escrito (0 es lo que da Android si nadie lo recibe)
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(ESCRIBIR);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receptor, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receptor, f);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(receptor);
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        View v = new View(this);  // invisible: no tapa nada
        v.setMinimumHeight(1);
        return v;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }
}
