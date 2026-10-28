package cn.tingxun.app;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Test APK's independent service process deliberately needs no target-app Kotlin runtime. */
public class FixtureTtsService extends TextToSpeechService {
    private volatile boolean stopped;
    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return "zh".equals(lang) || "zho".equals(lang) ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override protected int onLoadLanguage(String lang, String country, String variant) { return onIsLanguageAvailable(lang, country, variant); }
    @Override protected String[] onGetLanguage() { return new String[]{"zho", "CHN", ""}; }
    @Override public List<Voice> onGetVoices() {
        return Collections.singletonList(new Voice("fixture-zh", Locale.SIMPLIFIED_CHINESE,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet()));
    }
    @Override public int onIsValidVoiceName(String name) { return "fixture-zh".equals(name) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR; }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override protected void onStop() { stopped = true; }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopped = false;
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        byte[] data = new byte[Math.min(3200, callback.getMaxBufferSize())];
        for (int i = 0; i < 10; i++) {
            if (stopped) { callback.error(); return; }
            if (callback.audioAvailable(data, 0, data.length) != TextToSpeech.SUCCESS) return;
        }
        callback.done();
    }
}
