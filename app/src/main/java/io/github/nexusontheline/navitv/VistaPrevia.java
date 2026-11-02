package io.github.nexusontheline.navitv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** Muestra el salvapantallas ya, sin esperar. Cualquier botón lo cierra. */
public class VistaPrevia extends Activity {
    private Pase pase;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pase = new Pase(this);
        setContentView(pase.vista());
        pase.empezar();
    }

    @Override
    protected void onPause() {
        if (pase != null) pase.parar();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();
        return true;
    }
}
