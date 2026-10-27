# Render governor

What reaches the G2 display, and how often. `RenderGovernor` (glasses-ui module) sits between
`GlassesCaptionSink` and `GlassesGateway.show`.

## Why

Every page is a Bluetooth write to the right lens, and the G2 takes a text update only every few
hundred milliseconds. Finished captions arrive about once per sentence, which is fine, but live
speech (the speech engine's partial result) changes many times a second. Sent as-is, partials
queue up behind each other and the glasses fall seconds behind the speaker.

## Rules

| Page | What the governor does |
|---|---|
| Same as the last page sent | Dropped (counted as *unchanged*) |
| Urgent: a finished caption, Clear | Sent at once; replaces anything waiting |
| Live speech (partial) | Sent at most once per 300 ms. While one waits, a newer one replaces it (counted as *merged*); the newest is sent when the 300 ms are over |

The interval is `RenderGovernor.DEFAULT_MIN_INTERVAL_MS`. The governor has no Android code: time
and timers are passed in (`clock`, `Scheduler`), so it is unit tested with a fake clock.

Test messages from the Glasses tab go straight to the gateway; the sink then calls
`forgetShown()` so the next caption page is sent even if it equals the one before the test.

## UI (Glasses tab)

- **Show captions on glasses**: as before.
- **Show live speech on glasses** (new, off by default): the words being spoken appear on the
  bottom line, after `… `, until their caption arrives. They are in the spoken language, not yet
  translated.
- **Render governor: N pages sent · M merged · K unchanged skipped**: live counters, to check the
  glasses are not flooded.

On the Captions tab, Whisper (which has no live partial) shows a hint instead of an empty
*Hearing* line. Each engine is now created with the selected spoken language
(`AsrEngine.create(context, language)`).
