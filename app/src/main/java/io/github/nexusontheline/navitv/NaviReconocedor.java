package io.github.nexusontheline.navitv;

import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Android exige que un asistente traiga su propio reconocedor de voz.
 * Este no reconoce nada: le pasa el trabajo al de Google TV, así las apps
 * que buscan por voz (como TizenTube) siguen funcionando igual.
 */
public class NaviReconocedor extends RecognitionService {
    private SpeechRecognizer real;

    @Override
    protected void onStartListening(Intent intent, Callback cb) {
        if (real == null) real = SpeechRecognizer.createSpeechRecognizer(this, NaviSesion.RECONOCEDOR_GOOGLE);
        real.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { try { cb.readyForSpeech(b); } catch (RemoteException ignored) {} }
            @Override public void onBeginningOfSpeech() { try { cb.beginningOfSpeech(); } catch (RemoteException ignored) {} }
            @Override public void onRmsChanged(float v) { try { cb.rmsChanged(v); } catch (RemoteException ignored) {} }
            @Override public void onBufferReceived(byte[] b) { try { cb.bufferReceived(b); } catch (RemoteException ignored) {} }
            @Override public void onEndOfSpeech() { try { cb.endOfSpeech(); } catch (RemoteException ignored) {} }
            @Override public void onError(int e) { try { cb.error(e); } catch (RemoteException ignored) {} }
            @Override public void onResults(Bundle b) { try { cb.results(b); } catch (RemoteException ignored) {} }
            @Override public void onPartialResults(Bundle b) { try { cb.partialResults(b); } catch (RemoteException ignored) {} }
            @Override public void onEvent(int t, Bundle b) {}
        });
        real.startListening(intent);
    }

    @Override
    protected void onStopListening(Callback cb) {
        if (real != null) real.stopListening();
    }

    @Override
    protected void onCancel(Callback cb) {
        if (real != null) real.cancel();
    }

    @Override
    public void onDestroy() {
        if (real != null) real.destroy();
        super.onDestroy();
    }
}
