package com.example.g2glasses.pipeline

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.SpeechStreamService
import org.vosk.android.StorageService
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [SpeechToText] backed by Vosk. The model ships in app/src/main/assets/[modelAssetDir]
 * (see scripts/fetch_models.*) and is copied to app storage on first launch.
 *
 * Call everything from the main thread; Vosk delivers its callbacks there too.
 */
class VoskSpeechToText(
    private val context: Context,
    private val modelAssetDir: String = "model-pt",
) : SpeechToText {

    override val name = "vosk"

    private val _partials = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val partials: SharedFlow<String> = _partials.asSharedFlow()

    private val _finals = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val finals: SharedFlow<String> = _finals.asSharedFlow()

    private val _streamEnded = MutableSharedFlow<StreamEnd>(extraBufferCapacity = 8)
    override val streamEnded: SharedFlow<StreamEnd> = _streamEnded.asSharedFlow()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var streamService: SpeechStreamService? = null

    override suspend fun prepare() {
        val m = unpackModel()
        model = m
        recognizer = Recognizer(m, SAMPLE_RATE)
        Log.i(TAG, "Vosk ready: model '$modelAssetDir'")
    }

    override fun startMic() {
        val rec = checkNotNull(recognizer) { "Call prepare() first" }
        stop()
        speechService = SpeechService(rec, SAMPLE_RATE).also { it.startListening(Listener(null)) }
    }

    override fun startFromWav(wav: InputStream, label: String) {
        val rec = checkNotNull(recognizer) { "Call prepare() first" }
        stop()
        val counting = CountingInputStream(wav)
        skipFully(counting, MetricsFormat.WAV_HEADER_BYTES.toLong())
        val run = WavRun(label, counting, SystemClock.elapsedRealtime())
        streamService = SpeechStreamService(rec, counting, SAMPLE_RATE).also { it.start(Listener(run)) }
    }

    override fun stop() {
        speechService?.let { it.stop(); it.shutdown() }
        speechService = null
        streamService?.stop()
        streamService = null
    }

    override fun release() {
        stop()
        recognizer?.close(); recognizer = null
        model?.close(); model = null
    }

    private class WavRun(val label: String, val bytes: CountingInputStream, val startedAtMs: Long)

    /**
     * One listener per start, so a mic's late onFinalResult (posted when it is stopped) can't be
     * mistaken for the end of a WAV run that started right after it.
     */
    private inner class Listener(private val wav: WavRun?) : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = VoskResults.parse(hypothesis, "partial")
            if (text.isNotEmpty()) _partials.tryEmit(text)
        }

        // Fires at each pause (end of a sentence).
        override fun onResult(hypothesis: String?) = submit(VoskResults.parse(hypothesis, "text"))

        // Fires once when stop() is called or a WAV file ends: flush the last words.
        override fun onFinalResult(hypothesis: String?) {
            submit(VoskResults.parse(hypothesis, "text"))
            finishWav()
        }

        override fun onError(e: Exception?) {
            Log.e(TAG, "Vosk error", e)
            finishWav()
        }

        override fun onTimeout() {
            Log.w(TAG, "Vosk timeout")
            finishWav()
        }

        private fun submit(text: String) {
            if (VoskResults.isUseful(text)) _finals.tryEmit(text)
        }

        private fun finishWav() {
            val run = wav ?: return
            val end = StreamEnd(
                label = run.label,
                audioSeconds = MetricsFormat.audioSeconds(run.bytes.count),
                wallSeconds = (SystemClock.elapsedRealtime() - run.startedAtMs) / 1000.0,
            )
            Metrics.wavDone(end)
            runCatching { run.bytes.close() }
            _streamEnded.tryEmit(end)
        }
    }

    private suspend fun unpackModel(): Model = suspendCancellableCoroutine { cont ->
        StorageService.unpack(
            context, modelAssetDir, "model",
            { m -> cont.resume(m) },
            { e -> cont.resumeWithException(e) },
        )
    }

    /** Counts every byte read, header included, so audio length is measured rather than assumed. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    companion object {
        private const val TAG = "TranslationPipeline"
        private const val SAMPLE_RATE = 16000f
    }
}
