package com.example.g2glasses.pipeline

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * [SpeechToText] backed by NVIDIA Parakeet TDT 0.6B v3 (multilingual, int8) through sherpa-onnx.
 *
 * Parakeet is not a streaming model, so Silero VAD cuts the audio at pauses and each finished
 * segment is decoded as one final. The "Hearing" partial re-decodes the current utterance about
 * once a second. All native work runs on one worker thread; results are posted to the main
 * thread in order, like Vosk's callbacks.
 *
 * The model is ~670 MB, so it is not in the APK: scripts/fetch_parakeet.* pushes it to
 * /sdcard/Android/data/<package>/files/[MODEL_DIR].
 */
class ParakeetSpeechToText(private val context: Context) : SpeechToText {

    override val name = "parakeet"

    private val _partials = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val partials: SharedFlow<String> = _partials.asSharedFlow()

    private val _finals = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val finals: SharedFlow<String> = _finals.asSharedFlow()

    private val _streamEnded = MutableSharedFlow<StreamEnd>(extraBufferCapacity = 8)
    override val streamEnded: SharedFlow<StreamEnd> = _streamEnded.asSharedFlow()

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var worker: Thread? = null

    @Volatile private var running = false
    @Volatile private var released = false

    /** Where scripts/fetch_parakeet.* puts the model files. */
    val modelDir: File get() = File(context.getExternalFilesDir(null), MODEL_DIR)

    override suspend fun prepare() {
        val (rec, v) = withContext(Dispatchers.IO) { load(modelDir) }
        if (released) {
            rec.release(); v.release()
            throw CancellationException("released while loading")
        }
        recognizer = rec
        vad = v
        Log.i(TAG, "Parakeet ready: ${modelDir.absolutePath}")
    }

    private fun load(dir: File): Pair<OfflineRecognizer, Vad> {
        dir.mkdirs()
        val missing = REQUIRED_FILES.filterNot { File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Parakeet model not on this phone (missing ${missing.joinToString()}). " +
                    "Run scripts\\fetch_parakeet.ps1, or switch back to Vosk."
            )
        }
        fun path(f: String) = File(dir, f).absolutePath
        val recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = path("encoder.int8.onnx"),
                        decoder = path("decoder.int8.onnx"),
                        joiner = path("joiner.int8.onnx"),
                    ),
                    tokens = path("tokens.txt"),
                    modelType = "nemo_transducer",
                    numThreads = NUM_THREADS,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
        )
        val vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = path("silero_vad.onnx"),
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,  // a pause this long ends a sentence
                    minSpeechDuration = 0.25f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 15f,    // long announcements stay one sentence
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            )
        )
        return recognizer to vad
    }

    // The caller (MainActivity) requests RECORD_AUDIO before starting the mic.
    @SuppressLint("MissingPermission")
    override fun startMic() {
        val rec = checkNotNull(recognizer) { "Call prepare() first" }
        val v = checkNotNull(vad)
        stop()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Several seconds of headroom: the same thread decodes, so reads pause while a sentence decodes.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE * 2 * 5),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        running = true
        worker = thread(name = "parakeet-mic") { micLoop(rec, v, record) }
    }

    override fun startFromWav(wav: InputStream, label: String) {
        val rec = checkNotNull(recognizer) { "Call prepare() first" }
        val v = checkNotNull(vad)
        stop()
        running = true
        worker = thread(name = "parakeet-wav") { wavLoop(rec, v, wav, label) }
    }

    /** Stops input and waits for the last sentence to be decoded, so its final is already posted. */
    override fun stop() {
        running = false
        worker?.join(STOP_TIMEOUT_MS)
        worker = null
    }

    override fun release() {
        stop()
        released = true
        recognizer?.release(); recognizer = null
        vad?.release(); vad = null
    }

    private fun micLoop(rec: OfflineRecognizer, v: Vad, record: AudioRecord) {
        val shorts = ShortArray(WINDOW)
        val utterance = ArrayList<FloatArray>()  // audio of the sentence in progress, for partials
        val preRoll = ArrayDeque<FloatArray>()
        var inSpeech = false
        var samplesSincePartial = 0
        try {
            record.startRecording()
            while (running) {
                val n = readFully(record, shorts)
                if (n <= 0) break
                val chunk = FloatArray(n) { shorts[it] / 32768f }
                v.acceptWaveform(chunk)

                if (v.isSpeechDetected()) {
                    if (!inSpeech) {
                        inSpeech = true
                        utterance.clear(); utterance.addAll(preRoll)
                        samplesSincePartial = 0
                    }
                    utterance += chunk
                    samplesSincePartial += n
                    val total = utterance.sumOf { it.size }
                    if (samplesSincePartial >= PARTIAL_EVERY_SAMPLES && total <= PARTIAL_MAX_SAMPLES) {
                        samplesSincePartial = 0
                        val text = decode(rec, concat(utterance))
                        if (text.isNotEmpty()) main.post { _partials.tryEmit(text) }
                    }
                } else {
                    preRoll.addLast(chunk)
                    while (preRoll.size > PRE_ROLL_WINDOWS) preRoll.removeFirst()
                }
                if (emitSegments(rec, v)) {
                    inSpeech = false
                    utterance.clear()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parakeet mic error", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            // Like Vosk's onFinalResult on stop: flush the last words.
            runCatching { v.flush(); emitSegments(rec, v) }
            v.reset()
        }
    }

    private fun wavLoop(rec: OfflineRecognizer, v: Vad, wav: InputStream, label: String) {
        val startedAt = SystemClock.elapsedRealtime()
        var bytes = 0L
        try {
            bytes += skipHeader(wav)
            val buf = ByteArray(WINDOW * 2)
            while (running) {
                val n = readFully(wav, buf)
                if (n <= 0) break
                bytes += n
                val samples = n / 2
                val chunk = FloatArray(samples) {
                    ((buf[2 * it].toInt() and 0xFF) or (buf[2 * it + 1].toInt() shl 8)).toShort() / 32768f
                }
                v.acceptWaveform(chunk)
                emitSegments(rec, v)
            }
            v.flush()
            emitSegments(rec, v)
        } catch (e: Exception) {
            Log.e(TAG, "Parakeet WAV error", e)
        } finally {
            v.reset()
            runCatching { wav.close() }
            val end = StreamEnd(
                label = label,
                audioSeconds = MetricsFormat.audioSeconds(bytes),
                wallSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0,
            )
            Metrics.wavDone(end)
            // Posted after this file's finals, so the pipeline sees them first.
            main.post { _streamEnded.tryEmit(end) }
        }
    }

    /** Decodes every finished VAD segment and posts it as a final. True if there was one. */
    private fun emitSegments(rec: OfflineRecognizer, v: Vad): Boolean {
        var any = false
        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            any = true
            val text = decode(rec, segment.samples)
            if (VoskResults.isUseful(text)) main.post { _finals.tryEmit(text) }
        }
        return any
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray): String {
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            return rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun concat(chunks: List<FloatArray>): FloatArray {
        val out = FloatArray(chunks.sumOf { it.size })
        var i = 0
        for (c in chunks) { c.copyInto(out, i); i += c.size }
        return out
    }

    private fun readFully(record: AudioRecord, buf: ShortArray): Int {
        var n = 0
        while (n < buf.size && running) {
            val r = record.read(buf, n, buf.size - n)
            if (r < 0) return if (n > 0) n else r
            n += r
        }
        return n
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return if (n == 0) -1 else n
    }

    private fun skipHeader(input: InputStream): Long {
        val header = ByteArray(MetricsFormat.WAV_HEADER_BYTES)
        return readFully(input, header).coerceAtLeast(0).toLong()
    }

    companion object {
        private const val TAG = "TranslationPipeline"
        const val MODEL_DIR = "parakeet-pt"
        private val REQUIRED_FILES =
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "silero_vad.onnx")
        private const val SAMPLE_RATE = 16000
        private const val WINDOW = 512                         // Silero VAD window, 32 ms
        private const val NUM_THREADS = 4
        private const val PRE_ROLL_WINDOWS = 10                // ~0.3 s kept before speech starts
        private const val PARTIAL_EVERY_SAMPLES = SAMPLE_RATE  // re-decode the partial about once a second
        private const val PARTIAL_MAX_SAMPLES = SAMPLE_RATE * 10
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
