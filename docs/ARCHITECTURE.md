# Architecture

How data moves through the Android app (`G2glasses/`). Boxes are code modules; the subgraphs are
the three Gradle modules. Solid arrows carry speech and captions; dotted arrows carry status and
events back from the glasses.

```mermaid
flowchart TB
    mic["Phone microphone"] --> vosk & parakeet
    wav["Test WAVs (assets/test)"] --> vosk & parakeet

    subgraph app["app"]
        vosk["VoskSpeechToText<br/>streaming · Portuguese"]
        parakeet["ParakeetSpeechToText<br/>VAD-cut sentences · 25 languages"]
        choice["Engine + language<br/>(MainActivity)"] -.-> pipeline
        vosk -- finals --> pipeline
        parakeet -- finals --> pipeline
        pipeline["TranslationPipeline<br/>ordered queue · ML Kit to English"] <--> mlkit["ML Kit Translate<br/>on-device packs"]
        pipeline --> caption["Caption"]
        vosk & parakeet -. live partial .-> captionsTab
        caption --> captionsTab["Captions tab"]
        caption --> metrics["Metrics (Logcat)"]
        caption --> sink["GlassesCaptionSink"]
        glassesTab["Glasses tab<br/>test messages · log"]
    end

    metrics --> score["score_metrics.py (PC)"]

    subgraph ui["glasses-ui"]
        screen["CaptionScreen"] --> layout["CaptionLayout"] --> page["GlassesPage"]
    end
    sink --> screen
    glassesTab -- test message --> page

    subgraph gw["g2-gateway"]
        gateway["G2BleGateway<br/>connect · heartbeats · text queue"] --> msgs["G2Messages + Packets"] --> lens["G2Lens × 2 (GATT)"]
        responses["G2Responses"]
    end
    page --> gateway
    lens --> glasses["Even Realities G2<br/>left + right lens"]

    glasses -.-> lens -.-> responses -.-> gateway -. state + log .-> glassesTab
```

Interfaces between modules: `SpeechToText` (engines), `CaptionSink` (caption outputs),
`GlassesScreen` (on-glasses UI) and `GlassesGateway` (the BLE link).
