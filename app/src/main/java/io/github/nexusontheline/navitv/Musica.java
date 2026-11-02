package io.github.nexusontheline.navitv;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import java.util.List;

/** Lo que se está reproduciendo en la tele (necesita el permiso de «Escucha»). */
final class Musica {
    private Musica() {}

    /** La app que está sonando ahora (con título), o null. */
    static MediaController sonando(Context ctx) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = msm.getActiveSessions(new ComponentName(ctx, Escucha.class));
            for (MediaController mc : l) {
                PlaybackState ps = mc.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING && mc.getMetadata() != null) return mc;
            }
        } catch (SecurityException ignored) {
            // sin permiso de notificaciones
        }
        return null;
    }

    /** Posición actual en ms, contando el tiempo desde la última actualización. */
    static long posicion(MediaController mc) {
        PlaybackState ps = mc.getPlaybackState();
        if (ps == null) return 0;
        long pos = ps.getPosition();
        if (ps.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
        }
        return pos;
    }

    static String texto(MediaMetadata md, String... claves) {
        if (md == null) return "";
        for (String k : claves) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }
}
