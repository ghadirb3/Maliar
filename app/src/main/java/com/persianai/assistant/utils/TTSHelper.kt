// در فایل TTSHelper.kt
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// ... کدهای قبلی کلاس ...

// 1. تابع جدید برای درخواست به GapGPT
suspend fun speakWithGapGPT(text: String) {
    // آدرس دقیق بر اساس مستندات gapgpt
    val url = "https://api.gapgpt.ir/v1/audio/speech"
    val jsonBody = """
        {
            "model": "gpt-4o-mini-tts",
            "input": "$text",
            "voice": "nova" // یا alloy، echo، fable، onyx، shimmer
        }
    """.trimIndent()

    // در اینجا با OkHttp یا Retrofit درخواست POST را ارسال کرده 
    // و فایل صوتی (mp3) را با MediaPlayer پخش کنید.
    // مطمئن شوید MediaPlayer.setOnCompletionListener تنظیم شده است تا 
    // متوجه اتمام پخش شوید.
}

// 2. اگر از سیستم TTS داخلی اندروید (آفلاین) استفاده می‌کنید، این کد تضمین می‌کند 
// ضبط زودتر از موعد شروع نشود:
suspend fun speakAndWait(text: String, utteranceId: String = "TTS_ID") = suspendCancellableCoroutine<Unit> { continuation ->
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            if (continuation.isActive) continuation.resume(Unit)
        }
        override fun onError(utteranceId: String?) {
            if (continuation.isActive) continuation.resume(Unit)
        }
    })
    
    val params = Bundle()
    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
}
