package io.github.nexusontheline.navitv;

import android.service.dreams.DreamService;

/** El salvapantallas de la tele: imágenes al azar de Safebooru. */
public class Salvapantallas extends DreamService {
    private Pase pase;
    /** Para que NAVI no aparezca encima del salvapantallas. */
    public static volatile boolean activo;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // cualquier botón del control lo cierra
        setFullscreen(true);
        setScreenBright(true);
        pase = new Pase(this);
        setContentView(pase.vista());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        activo = true;
        pase.empezar();
    }

    @Override
    public void onDreamingStopped() {
        activo = false;
        if (pase != null) pase.parar();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        activo = false;
        if (pase != null) pase.parar();
        super.onDetachedFromWindow();
    }
}
