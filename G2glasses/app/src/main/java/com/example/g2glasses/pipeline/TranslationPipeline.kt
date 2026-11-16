package com.example.g2glasses.pipeline

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline speech -> English captions.
 *
 *   mic (or WAV) -> [SpeechToText] -> ML Kit ([sourceLang] -> en) -> [captions]
 *
 * When [sourceLang] is English the text is passed through without ML Kit.
 *
 * Everything runs on the main thread: Vosk calls back there and ML Kit work happens off-thread
 * behind [Task]s, so a single thread keeps finals, translations and WAV ends in spoken order.
 */
class TranslationPipeline(
    context: Context,
    private val stt: SpeechToText = VoskSpeechToText(context, "model-pt"),
    private val sourceLang: String = TranslateLanguage.PORTUGUESE, // the language being spoken
    private val targetLang: String = TranslateLanguage.ENGLISH,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _captions = MutableSharedFlow<Caption>(extraBufferCapacity = 64)
    /** One record per finished sentence, in spoken order. */
    val captions: SharedFlow<Caption> = _captions.asSharedFlow()

    /** Which speech engine this pipeline runs, e.g. "vosk" or "parakeet". */
    val engineName: String get() = stt.name

    /** Live partial transcript in the source language (not translated). */
    val partials: SharedFlow<String> get() = stt.partials

    /** null when no translation is needed (speech is already in the target language). */
    private val translator: Translator? =
        if (sourceLang == targetLang) null
        else Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
        )

    private sealed interface Item {
        class Final(val text: String, val asrFinalAtMs: Long) : Item
        class Barrier(val reached: CompletableDeferred<Unit>) : Item
    }

    // Finished sentences are queued so translations come out in the order spoken.
    private val pending = Channel<Item>(Channel.UNLIMITED)
    private var nextId = 1
    private var wavDone: CompletableDeferred<Unit>? = null
    private var wavCaptions: MutableList<Caption>? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Timestamped on arrival; on the main thread this is right after Vosk's callback.
            stt.finals.collect { pending.send(Item.Final(it, SystemClock.elapsedRealtime())) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Queued behind the WAV's last final, so it is reached only once that is translated.
            stt.streamEnded.collect { wavDone?.let { done -> pending.send(Item.Barrier(done)) } }
        }
        scope.launch {
            for (item in pending) {
                when (item) {
                    is Item.Final -> translate(item)
                    is Item.Barrier -> item.reached.complete(Unit)
                }
            }
        }
    }

    private suspend fun translate(item: Item.Final) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            val english = translator?.translate(item.text)?.await() ?: item.text
            val caption = Caption(
                id = nextId++,
                source = item.text,
                english = english,
                language = sourceLang,
                asrFinalAtMs = item.asrFinalAtMs,
                translateMs = SystemClock.elapsedRealtime() - t0,
            )
            Log.d(TAG, "[$sourceLang] ${caption.source}  ->  [$targetLang] ${caption.english}")
            Metrics.caption(caption)
            wavCaptions?.add(caption)
            _captions.emit(caption)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed for: ${item.text}", e)
        }
    }

    /**
     * Call once before starting. Unpacks the Vosk model and makes sure the ML Kit language packs
     * are on the device. The ML Kit download needs internet the FIRST time only.
     */
    suspend fun prepare() {
        val t0 = SystemClock.elapsedRealtime()
        stt.prepare()
        val modelUnpackMs = SystemClock.elapsedRealtime() - t0

        val t1 = SystemClock.elapsedRealtime()
        translator?.let {
            it.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            // Warm-up: the first translate() loads the model; keep that out of the first caption's translateMs.
            it.translate("ok").await()
        }
        val mlkitReadyMs = SystemClock.elapsedRealtime() - t1

        Metrics.startup(stt.name, sourceLang, modelUnpackMs, mlkitReadyMs)
        Log.i(TAG, "Pipeline ready: ${stt.name}, ML Kit $sourceLang -> $targetLang")
    }

    /** Live microphone input. Requires RECORD_AUDIO to already be granted. */
    fun startMic() = stt.startMic()

    fun stop() = stt.stop()

    /** Suspends until every final queued so far has been translated. */
    suspend fun drain() {
        val reached = CompletableDeferred<Unit>()
        pending.send(Item.Barrier(reached))
        reached.await()
    }

    /**
     * Runs one 16 kHz mono 16-bit WAV through the same pipeline as the mic and suspends until its
     * last sentence is translated. Logs a RESULT line and returns that file's captions.
     */
    suspend fun runWav(label: String, wav: InputStream): List<Caption> {
        val done = CompletableDeferred<Unit>()
        val collected = mutableListOf<Caption>()
        wavDone = done
        wavCaptions = collected
        try {
            stt.startFromWav(wav, label)
            done.await()
        } finally {
            wavDone = null
            wavCaptions = null
        }
        Metrics.result(
            label,
            collected.joinToString(" ") { it.source },
            collected.joinToString(" ") { it.english },
        )
        return collected
    }

    fun release() {
        stt.release()
        pending.close()
        translator?.close()
        scope.cancel()
    }

    // Lets us "wait" for an ML Kit task without needing an extra library.
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { e -> cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }

    companion object {
        private const val TAG = "TranslationPipeline"
    }
}
