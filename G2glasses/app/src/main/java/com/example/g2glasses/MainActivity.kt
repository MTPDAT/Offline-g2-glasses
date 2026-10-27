package com.example.g2glasses

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.g2glasses.pipeline.AsrEngine
import com.example.g2glasses.pipeline.Caption
import com.example.g2glasses.gateway.G2BleGateway
import com.example.g2glasses.gateway.GatewayLogEntry
import com.example.g2glasses.gateway.GlassesGateway
import com.example.g2glasses.glassesui.GlassesPage
import com.example.g2glasses.glassesui.RenderGovernor
import com.example.g2glasses.pipeline.CaptionSink
import com.example.g2glasses.pipeline.GlassesCaptionSink
import com.example.g2glasses.pipeline.LogcatCaptionSink
import com.example.g2glasses.pipeline.MetricsFormat
import com.example.g2glasses.pipeline.SourceLanguage
import com.example.g2glasses.pipeline.TranslationPipeline
import com.example.g2glasses.pipeline.WavFormat
import com.example.g2glasses.ui.theme.G2GlassesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InputStream

class MainActivity : ComponentActivity() {

    private lateinit var pipeline: TranslationPipeline

    // The glasses: connection in the g2-gateway module, layout in glasses-ui.
    private lateinit var gateway: GlassesGateway
    private lateinit var glassesSink: GlassesCaptionSink
    private lateinit var captionSinks: List<CaptionSink>
    private val gatewayLog = mutableStateListOf<GatewayLogEntry>()
    private var mirrorCaptions by mutableStateOf(true)
    private var livePartials by mutableStateOf(false)
    private var governorStats by mutableStateOf(RenderGovernor.Stats())
    private var tab by mutableStateOf(Tab.CAPTIONS)

    private enum class Tab(val label: String) { CAPTIONS("Captions"), GLASSES("Glasses") }

    // What the screen shows
    private var status by mutableStateOf("Preparing…")
    private var online by mutableStateOf(false)
    private var hearing by mutableStateOf("")
    private var ready by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var testing by mutableStateOf(false)
    private var preparing by mutableStateOf(false)
    private var engine by mutableStateOf(AsrEngine.VOSK)
    private var language by mutableStateOf(SourceLanguage.PORTUGUESE)
    private var languageMenuOpen by mutableStateOf(false)
    private val captions = mutableStateListOf<Caption>()

    private var testJob: Job? = null
    private var pipelineJob: Job? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMic()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                status = "Microphone permission denied. Allow it in Settings → Apps → G2 glasses → Permissions."
            } else {
                status = "Microphone permission denied. Tap Start mic to ask again."
            }
        }

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) gateway.connect()
            else gatewayLog.add(0, GatewayLogEntry(android.os.SystemClock.elapsedRealtime(),
                "Nearby devices permission denied; allow it in Settings → Apps → G2 glasses to connect"))
        }

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshOnline()
        override fun onLost(network: Network) = refreshOnline()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshOnline()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        engine = prefs.getString(PREF_ENGINE, null)
            ?.let { saved -> AsrEngine.entries.firstOrNull { it.name == saved } }
            ?: AsrEngine.VOSK
        language = SourceLanguage.fromCode(prefs.getString(PREF_LANGUAGE, null)) ?: SourceLanguage.PORTUGUESE
        if (!engine.supports(language)) engine = AsrEngine.PARAKEET

        gateway = G2BleGateway(this)
        mirrorCaptions = prefs.getBoolean(PREF_MIRROR, true)
        livePartials = prefs.getBoolean(PREF_LIVE_PARTIALS, false)
        glassesSink = GlassesCaptionSink(gateway).also {
            it.enabled = mirrorCaptions
            it.showPartials = livePartials
            it.governor.onStats = { s -> governorStats = s }
        }
        captionSinks = listOf(LogcatCaptionSink(), glassesSink)
        lifecycleScope.launch {
            gateway.log.collect { e ->
                gatewayLog.add(0, e)
                if (gatewayLog.size > 200) gatewayLog.removeAt(gatewayLog.lastIndex)
            }
        }

        setContent { G2GlassesTheme { Screen() } }

        connectivity.registerDefaultNetworkCallback(networkCallback)
        refreshOnline()

        startPipeline()
    }

    /** Speech in [language] ([engine]) -> English captions. */
    private fun startPipeline() {
        pipeline = TranslationPipeline(context = this, stt = engine.create(this, language), sourceLang = language.code)
        val p = pipeline
        ready = false
        preparing = true
        hearing = ""
        // A language's first use downloads its ML Kit pack (~30 MB), which needs internet once.
        status = "Preparing ${engine.label} · ${language.englishName}…"

        pipelineJob = lifecycleScope.launch {
            launch {
                p.partials.collect {
                    hearing = it
                    glassesSink.onPartial(it)
                }
            }
            launch {
                p.captions.collect { c ->
                    captions.add(0, c)
                    hearing = ""
                    captionSinks.forEach { it.onCaption(c) }
                }
            }
            try {
                p.prepare()
                ready = true
                if (hasMicPermission()) startMic() else status = "Paused — tap Start mic"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                status = "Setup failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                preparing = false
            }
        }
    }

    /** Prepares a pipeline with [next]; captions so far are kept. */
    private fun switchEngine(next: AsrEngine) {
        if (next == engine || preparing || testing || !next.supports(language)) return
        engine = next
        restartPipeline()
    }

    /** Prepares a pipeline for [next], moving to Parakeet if the current engine can't hear it. */
    private fun selectLanguage(next: SourceLanguage) {
        languageMenuOpen = false
        if (next == language || preparing || testing) return
        language = next
        if (!engine.supports(next)) engine = AsrEngine.PARAKEET
        restartPipeline()
    }

    /** Tears down the current pipeline and prepares one for the current [engine] and [language]. */
    private fun restartPipeline() {
        if (listening) stopMic()
        pipelineJob?.cancel()
        pipeline.release()
        prefs.edit()
            .putString(PREF_ENGINE, engine.name)
            .putString(PREF_LANGUAGE, language.code)
            .apply()
        startPipeline()
    }

    private fun connectGlasses() {
        if (G2BleGateway.hasPermissions(this)) gateway.connect()
        else bluetoothPermission.launch(G2BleGateway.requiredPermissions())
    }

    private fun onMirrorCaptionsChanged(on: Boolean) {
        mirrorCaptions = on
        glassesSink.enabled = on
        prefs.edit().putBoolean(PREF_MIRROR, on).apply()
    }

    private fun onLivePartialsChanged(on: Boolean) {
        livePartials = on
        glassesSink.showPartials = on
        prefs.edit().putBoolean(PREF_LIVE_PARTIALS, on).apply()
    }

    /** Test messages bypass the caption sink, so it must not assume the glasses still show its last page. */
    private fun showOnGlassesDirectly(text: String) {
        glassesSink.forgetShown()
        gateway.show(GlassesPage.fullScreenText(text))
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        pipeline.release()
        glassesSink.governor.cancelPending()
        gateway.release()
        super.onDestroy()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun onMicButton() {
        when {
            listening -> stopMic()
            hasMicPermission() -> startMic()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMic() {
        try {
            pipeline.startMic()
        } catch (e: Exception) {
            Log.e(TAG, "Mic start failed", e)
            status = "Mic failed: ${e.message ?: e.javaClass.simpleName}"
            return
        }
        listening = true
        status = "Listening"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopMic() {
        pipeline.stop()
        listening = false
        status = "Paused"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Feeds every assets/test/\*.wav through the same pipeline as the mic, one after another. */
    private fun onTestButton() {
        if (testJob?.isActive == true) {
            testJob?.cancel()
            return
        }
        val files = (runCatching { assets.list("test") }.getOrNull() ?: emptyArray())
            .filter { it.endsWith(".wav", ignoreCase = true) }
            .sorted()
        if (files.isEmpty()) {
            status = "No test WAVs in app/src/main/assets/test/"
            return
        }
        if (listening) stopMic()

        testJob = lifecycleScope.launch {
            testing = true
            try {
                // Let a just-stopped mic flush and translate its last sentence before the first file.
                delay(300)
                pipeline.drain()
                var ran = 0
                for ((i, name) in files.withIndex()) {
                    status = "Test WAV ${i + 1}/${files.size}: $name"
                    val problem = assets.open("test/$name").use { WavFormat.problem(readHeader(it)) }
                    if (problem != null) {
                        Log.w(TAG, "Skipping $name: $problem")
                        continue
                    }
                    assets.open("test/$name").use { pipeline.runWav(name, it) }
                    ran++
                }
                status = "Test WAVs done ($ran of ${files.size}). See Logcat tag Metrics."
            } catch (e: CancellationException) {
                pipeline.stop()
                status = "Paused"
                throw e
            } finally {
                testing = false
            }
        }
    }

    private fun readHeader(input: InputStream): ByteArray {
        val buf = ByteArray(MetricsFormat.WAV_HEADER_BYTES)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    private fun refreshOnline() {
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        runOnUiThread { online = isOnline }
    }

    @Composable
    private fun Screen() {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        status,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    NetworkChip(online)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in Tab.entries) {
                        FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                    }
                }
                HorizontalDivider(Modifier.padding(bottom = 8.dp))

                if (tab == Tab.GLASSES) {
                    GlassesPanel(
                        state = gateway.state.collectAsState().value,
                        log = gatewayLog,
                        mirrorCaptions = mirrorCaptions,
                        livePartials = livePartials,
                        governorStats = governorStats,
                        onConnect = ::connectGlasses,
                        onDisconnect = gateway::disconnect,
                        onForget = gateway::forgetGlasses,
                        onMirrorCaptionsChange = ::onMirrorCaptionsChanged,
                        onLivePartialsChange = ::onLivePartialsChanged,
                        onSendTest = ::showOnGlassesDirectly,
                        onClearGlasses = { showOnGlassesDirectly(" ") },
                        modifier = Modifier.weight(1f),
                    )
                    return@Column
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Engine:", style = MaterialTheme.typography.labelMedium)
                    for (e in AsrEngine.entries) {
                        FilterChip(
                            selected = engine == e,
                            onClick = { switchEngine(e) },
                            label = { Text(e.label) },
                            enabled = !preparing && !testing && e.supports(language),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Spoken:", style = MaterialTheme.typography.labelMedium)
                    Box {
                        OutlinedButton(
                            onClick = { languageMenuOpen = true },
                            enabled = !preparing && !testing,
                        ) { Text("${language.label}  ▾") }
                        DropdownMenu(
                            expanded = languageMenuOpen,
                            onDismissRequest = { languageMenuOpen = false },
                        ) {
                            for (l in SourceLanguage.ALL) {
                                DropdownMenuItem(
                                    text = { Text(l.label) },
                                    onClick = { selectLanguage(l) },
                                )
                            }
                        }
                    }
                    Text("→ English", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))

                Text("Hearing:", style = MaterialTheme.typography.labelMedium)
                Text(
                    hearing.ifEmpty {
                        // Whisper has no live partial; say so instead of an empty line.
                        if (engine == AsrEngine.WHISPER && listening) "… (Whisper shows each sentence after a pause)" else "…"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = ::onMicButton, enabled = ready && !testing) {
                        Text(if (listening) "Stop mic" else "Start mic")
                    }
                    OutlinedButton(onClick = ::onTestButton, enabled = ready) {
                        Text(if (testing) "Stop tests" else "Run test WAVs")
                    }
                    TextButton(onClick = { captions.clear(); hearing = ""; glassesSink.clear() }) { Text("Clear") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // New captions are inserted at the top; follow them so the newest is always visible.
                val listState = rememberLazyListState()
                val newestId = captions.firstOrNull()?.id
                LaunchedEffect(newestId) {
                    if (newestId != null) listState.animateScrollToItem(0)
                }
                LazyColumn(Modifier.weight(1f), state = listState) {
                    items(captions, key = { it.id }) { c -> CaptionRow(c) }
                }
            }
        }
    }

    @Composable
    private fun CaptionRow(c: Caption) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(c.english, style = MaterialTheme.typography.headlineSmall)
            Text(
                c.source,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${c.translateMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    @Composable
    private fun NetworkChip(online: Boolean) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (online) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Text(
                if (online) "Online" else "Offline",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_ENGINE = "asr_engine"
        private const val PREF_LANGUAGE = "source_language"
        private const val PREF_MIRROR = "mirror_captions"
        private const val PREF_LIVE_PARTIALS = "live_partials_on_glasses"
    }
}
