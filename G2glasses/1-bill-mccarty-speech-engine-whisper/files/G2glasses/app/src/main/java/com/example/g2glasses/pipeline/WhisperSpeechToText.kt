package com.example.g2glasses.pipeline

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
 * [SpeechToText] backed by OpenAI Whisper through whisper.cpp (built from source by
 * src/main/cpp/CMakeLists.txt, called through [WhisperNative]).
 *
 * Whisper is not a streaming model: like Parakeet, Silero VAD (from sherpa-onnx) cuts the audio at
 * pauses and each segment is decoded as one final. Whisper decodes too slowly on a phone to
 * re-decode a live partial, so this engine emits finals only. All native work runs on one worker
 * thread; results are posted to the main thread in order.
 *
 * Models are too big for the APK: scripts/fetch_whisper.* pushes one ggml model, silero_vad.onnx
 * and model.txt to /sdcard/Android/data/<package>/files/[MODEL_DIR]. [WhisperModelSelector] picks
 * which model to load. [language] null means Whisper detects the spoken language itself.
 */
class WhisperSpeechToText(
    private val context: Context,
    private val language: SourceLanguage? = null,
) : SpeechToText {

    private var model: WhisperModel? = null

    /** "whisper-<model>" once prepared, so Metrics lines say which model produced them. */
    override val name: String get() = model?.let { "whisper-${it.id}" } ?: "whisper"

    private val _partials = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val partials: SharedFlow<String> = _partials.asSharedFlow()

    private val _finals = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val finals: SharedFlow<String> = _finals.asSharedFlow()

    private val _streamEnded = MutableSharedFlow<StreamEnd>(extraBufferCapacity = 8)
    override val streamEnded: SharedFlow<StreamEnd> = _streamEnded.asSharedFlow()

    private val main = Handler(Looper.getMainLooper())
    private var whisper = 0L
    private var vad: Vad? = null
    private var worker: Thread? = null
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
    private val languageCode = language?.code?.takeIf { it in WhisperText.LANGUAGES } ?: ""

    @Volatile private var running = false
    @Volatile private var released = false

    /** Where scripts/fetch_whisper.* puts the model files. */
    val modelDir: File get() = File(context.getExternalFilesDir(null), MODEL_DIR)

    override suspend fun prepare() {
        val (picked, handle, v) = withContext(Dispatchers.IO) { load(modelDir) }
        if (released) {
            WhisperNative.nativeFree(handle); v.release()
            throw CancellationException("released while loading")
        }
        model = picked
        whisper = handle
        vad = v
        Log.i(TAG, "Whisper ready: ${picked.fileName}, $threads threads, lang=${languageCode.ifEmpty { "auto" }}, " +
            WhisperNative.nativeSystemInfo())
    }

    private fun load(dir: File): Triple<WhisperModel, Long, Vad> {
        dir.mkdirs()
        val present = dir.list()?.toSet().orEmpty()
        val preferred = File(dir, WhisperModelSelector.CHOICE_FILE).takeIf { it.isFile }
            ?.let { WhisperModel.fromId(it.readText()) }
        val picked = WhisperModelSelector.select(present, totalRamBytes(), preferred)
            ?: throw IllegalStateException(
                "No Whisper model on this phone. Run scripts\\fetch_whisper.ps1 (or fetch_whisper.sh), " +
                    "or switch to another engine."
            )
        if (VAD_FILE !in present) {
            throw IllegalStateException("Missing $VAD_FILE in ${dir.absolutePath}. Run scripts\\fetch_whisper.ps1.")
        }
        if (preferred != null && preferred != picked) {
            Log.w(TAG, "model.txt asks for ${preferred.id}; using ${picked.id} (file missing or not enough RAM)")
        }
        val handle = WhisperNative.nativeInit(File(dir, picked.fileName).absolutePath)
        check(handle != 0L) { "whisper.cpp could not load ${picked.fileName} (corrupt download?)" }
        val vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File(dir, VAD_FILE).absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,  // a pause this long ends a sentence
                    minSpeechDuration = 0.25f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 15f,    // well inside Whisper's 30 s window
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            )
        )
        return Triple(picked, handle, vad)
    }

    private fun totalRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem
    }

    // The caller (MainActivity) requests RECORD_AUDIO before starting the mic.
    @SuppressLint("MissingPermission")
    override fun startMic() {
        val v = checkNotNull(vad) { "Call prepare() first" }
        stop()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Several seconds of headroom: the same thread decodes, so reads pause while a sentence decodes.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE * 2 * 10),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        running = true
        worker = thread(name = "whisper-mic") { micLoop(v, record) }
    }

    override fun startFromWav(wav: InputStream, label: String) {
        val v = checkNotNull(vad) { "Call prepare() first" }
        stop()
        running = true
        worker = thread(name = "whisper-wav") { wavLoop(v, wav, label) }
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
        if (whisper != 0L) { WhisperNative.nativeFree(whisper); whisper = 0L }
        vad?.release(); vad = null
    }

    private fun micLoop(v: Vad, record: AudioRecord) {
        val shorts = ShortArray(WINDOW)
        try {
            record.startRecording()
            while (running) {
                val n = readFully(record, shorts)
                if (n <= 0) break
                v.acceptWaveform(FloatArray(n) { shorts[it] / 32768f })
                emitSegments(v)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Whisper mic error", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { v.flush(); emitSegments(v) }  // the last words before Stop
            v.reset()
        }
    }

    private fun wavLoop(v: Vad, wav: InputStream, label: String) {
        val startedAt = SystemClock.elapsedRealtime()
        var bytes = 0L
        try {
            bytes += readFully(wav, ByteArray(MetricsFormat.WAV_HEADER_BYTES)).coerceAtLeast(0)
            val buf = ByteArray(WINDOW * 2)
            while (running) {
                val n = readFully(wav, buf)
                if (n <= 0) break
                bytes += n
                v.acceptWaveform(FloatArray(n / 2) {
                    ((buf[2 * it].toInt() and 0xFF) or (buf[2 * it + 1].toInt() shl 8)).toShort() / 32768f
                })
                emitSegments(v)
            }
            v.flush()
            emitSegments(v)
        } catch (e: Exception) {
            Log.e(TAG, "Whisper WAV error", e)
        } finally {
            v.reset()
            runCatching { wav.close() }
            val end = StreamEnd(
                label = label,
                audioSeconds = MetricsFormat.audioSeconds(bytes),
                wallSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0,
            )
            Metrics.wavDone(end)
            main.post { _streamEnded.tryEmit(end) }  // after this file's finals
        }
    }

    /** Decodes every finished VAD segment and posts it as a final. */
    private fun emitSegments(v: Vad) {
        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            val text = WhisperText.clean(WhisperNative.transcribe(whisper, segment.samples, languageCode, threads))
            if (VoskResults.isUseful(text)) main.post { _finals.tryEmit(text) }
        }
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

    companion object {
        private const val TAG = "TranslationPipeline"
        const val MODEL_DIR = "whisper"
        const val VAD_FILE = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW = 512          // Silero VAD window, 32 ms
        private const val MAX_THREADS = 4       // more threads stop helping on big.LITTLE phones
        private const val STOP_TIMEOUT_MS = 10_000L
    }
}
