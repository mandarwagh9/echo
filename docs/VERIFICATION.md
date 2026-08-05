# Verification record

What was actually run, and what it proved. Dated 2026-08-05.

**Environment:** JDK 17 · Android SDK 36 · NDK 27.1.12297006 · CMake 3.22.1 ·
whisper.cpp v1.9.2 (`306c88f4`).

**Targets:** Pixel 9 (`tokay`, arm64-v8a, Android 17 / API 37) — the real device — and an
Android 16 (API 36) x86_64 emulator, used while the phone was unreachable over ADB.

---

## 1. Build

| | |
|---|---|
| whisper.cpp + ggml, NDK 27, `arm64-v8a` | compiles clean |
| whisper.cpp + ggml, NDK 27, `x86_64` | compiles clean |
| Native libs packaged | `libecho_whisper`, `libggml`, `libggml-base`, `libggml-cpu`, `libomp`, `libc++_shared` |
| Release APK | 49.9 MB, signed, installs |

## 2. JVM unit tests — 42 passing

- **`ChunkMathTest`** — the "every minute is recorded" guarantee. Asserts the split sizes
  always sum to the buffer length, walking a full chunk one buffer at a time, including the
  case where the chunk size is not a multiple of the buffer size, and where a buffer spans
  several chunks.
- **`WavWriterTest`** — header field correctness, size patching on close, value-exact round
  trip, offset writes, idempotent close, and recovery of a chunk truncated by a crash.
- **`VoiceActivityDetectorTest`** — digital silence and quiet room tone reject; sustained
  speech over a quiet floor accepts.
- **`TextToolsTest`** — tokenisation and stopwords across English *and* Devanagari, danda
  sentence splitting, TF-IDF distinctiveness, TextRank ordering, name heuristics.
- **`SummarySchedulerTest`** — next-11-PM arithmetic before, after and exactly at the target.
- **`SummaryFormatTest`** — duration, language-name and percentage rendering.

## 3. Instrumented tests — 11 passing on the Pixel 9

Run against the release variant on the physical phone (`Starting 11 tests on Pixel 9 - 17` →
`0 failed`), and previously on the emulator. Note the OS: **API 37, one level above the
`compileSdk` of 36**, so the whole suite is also evidence that nothing breaks on the next
Android release.

> **These tests are destructive.** The summary suite clears chunks, segments and summaries
> from the real app database, and Gradle uninstalls the app afterwards. `verify-on-device.ps1`
> therefore skips them unless `-RunTests` is passed explicitly — never run them on a phone
> holding recordings you want to keep.

| Test | Proves |
|---|---|
| `a_jniSymbolsResolveAndLibraryLoads` | JNI symbols resolve; `libomp`/`libggml*` DT_NEEDED chain loads |
| `b_wavFixtureReadsAsSixteenKhzMono` | WAV reader handles a real file |
| `c_vadDetectsSpeechInRealAudio` | VAD accepts genuine speech |
| `d_transcribesKnownSampleCorrectly` | whisper output is **verbatim correct** |
| `e_autoDetectFindsEnglish` | language auto-detection returns `en` |
| `f_pipelineTranscribesThenDeletesAudio` | **transcript committed, then audio deleted** |
| `g_silentChunkIsSkippedAndCleanedUp` | silent chunk skips whisper, still cleans up |
| `generatesASummaryForACodeSwitchedDay` | summary over mixed en/hi/mr, correct timeline |
| `devanagariTermsSurviveIntoTopics` | regression guard for the tokenizer bug below |
| `namesAreDetectedFromEnglishSpeech` | name detection incl. sentence-initial mentions |
| `h_fullSizeTenMinuteChunkSurvivesTheMemoryPath` | the **default** 10-min chunk: 9.6M samples, 19.2 MB written, 38.4 MB read back, deleted — no OOM |

Transcription of whisper.cpp's own `jfk.wav` fixture:

> `and so my fellow americans ask not what your country can do for you ask what you can do for your country.`

## 4. Live run on device

Recording started with the chunk length set to 1 minute:

```
03:25:24  AudioChunker: capture started (source=1, buffer=64000 B)     <- source 1 = MIC
03:26:25  RecordingService: closed chunk 1 (960000 samples)
03:26:28  TranscriptionPipeline: chunk 1 is silent (ratio=0.0); skipping whisper
```

Database immediately after:

```
1 | SILENT | audioDeleted=1 | filePath=NULL | sampleCount=960000
2 | RECORDING |            | chunk_1785880585026.wav
```

- `960000` samples is **exactly** 60 s × 16 000 Hz — rotation is sample-exact.
- A second chunk also closed at exactly 960 000 samples.
- Audio deleted and `filePath` nulled once the chunk reached a terminal state.
- Rotation continued into chunk 2 with no gap; stopping mid-chunk preserved the
  partial chunk (1024 samples) as PENDING rather than discarding it.
- `dumpsys` confirmed `isForeground=true types=0x00000080`
  (`FOREGROUND_SERVICE_TYPE_MICROPHONE`) with an ongoing, silent notification.

UI verified by screenshot: Today (idle + listening), Settings (models, language, capture,
summary, storage, danger zone), Summary (empty state and rendered summary).

## 5. The 11 PM chain, fired for real

With recording **off** — the common case at 11 PM, and the path that needs the `dataSync`
promotion because the microphone type would be refused from a background start.

`dumpsys alarm` first confirmed the alarm was armed as intended:

```
RTC_WAKEUP  tag=*walarm*:com.mandar.echo/.summary.SummaryAlarmReceiver
type=RTC_WAKEUP origWhen=2026-08-05 23:00:00.000 window=0 exactAllowReason=policy_permission
idle-options=[... temporaryAppAllowlistDuration=10000 ...]
```

`window=0` is a true exact alarm, and `exactAllowReason=policy_permission` confirms
`USE_EXACT_ALARM` was auto-granted with no runtime request. Firing the receiver:

```
SummaryScheduler: summary alarm fired
ActivityManager:  Background started FGS: Allowed ... act=com.mandar.echo.GENERATE_SUMMARY
SummaryScheduler: next summary at Wed Aug 05 23:00:00        <- re-armed for tomorrow
SummaryEngine:    summary for 2026-08-05: under a minute of talk · 67 words · English
ActivityManager:  Stop FGS ... / FGS stop call               <- stopSelf()
NotificationService: notification posted
```

Afterwards: no `RecordingService` in `dumpsys activity services` (so `stopSelf` fired and no
misleading "listening" notification is left behind), notification id 1003 posted on the
`summary` channel, and the summary persisted to the database. No `could not enter foreground`
was logged, so the `dataSync` promotion was accepted.

---

## Bugs this process found and fixed

1. **Devanagari tokenizer.** `[\p{L}\p{N}']+` excludes Unicode combining marks, so vowel
   signs and the virama split words: `मीटिंग` tokenised as `म`, `ट`, `ग`. This silently
   destroyed stopword removal and topic extraction for **every** Hindi and Marathi
   transcript. Caught by unit tests. Fixed by adding `\p{M}`.
2. **OOM reading a full chunk.** `readAsFloats` held a 19 MB byte array and a 38 MB float
   array simultaneously, plus a possible JNI copy — enough to OOM a mid-range phone on a
   real 10-minute chunk. Unit tests used ≤1000 samples and could never have caught it. Now
   streamed in 64 KB blocks.
3. **Wrong audio source.** `VOICE_RECOGNITION` applies near-field AGC/noise suppression that
   damages far-field ambient capture and is aggressively yielded to the assistant. Changed
   to `MIC`.
4. **11 PM summary could not start.** Promoting the service with the `microphone` foreground
   type from a background alarm is refused on Android 14+ (mic is while-in-use). The
   summary-only path now promotes as `dataSync` and calls `stopSelf()` when done.
5. **Lying notification.** A summary-only wake-up left a permanent "Preparing to listen"
   foreground notification while not recording.
6. **Duplicate monitor loops.** `startMonitors()` ran on every `onStartCommand`, compounding
   infinite loops on each service restart.
7. **"Install a model" notice never cleared** — memoised on a key that does not change when a
   download completes.
8. **Settings reported "Free space 0 B"** until recording had run once, reading as disk-full.
9. **Names starting a sentence were invisible.** The sentence-initial exclusion (needed to
   reject "Meeting", "Later") also rejected real names. Now two-pass: a word qualifies if it
   appears non-initially anywhere, then all its occurrences count.
10. **Topics biased against Indic languages.** A fixed "appears ≥2×" floor drops everything on
    a short day and under-represents Hindi/Marathi, which inflect heavily so surface forms
    repeat less. Threshold is now adaptive.
11. **"0 min of talk"** and **"0% of the day"** — durations and percentages derived from
    truncated whole minutes. Percentages now come from seconds, and sub-minute reads as
    "under a minute" / "<1%".

---

## 6. Throughput on the real phone

This was the one number that mattered and the one nothing could measure until the phone
appeared. `d2_baseModelSustainsRealtimeThroughput` measures the **model the app actually ships
with**, over ~66 s of audio — three full 30-second whisper windows, so the result is a
steady-state rate rather than an artefact of zero-padding a short clip:

```
BASE realtime factor: 5.29x  (66.0 s of audio in 12467 ms, 4 threads) on Pixel 9 / tokay
projected: a 10-minute chunk transcribes in 113 s
```

Model: `ggml-base-q5_1.bin`, 59,707,625 bytes, 4 threads (`cores - 2`, capped at 4).

**Read that as 5.3× under laboratory conditions, not as headroom in service.** The measurement
was taken on a cool, idle phone doing nothing else; in real use the same work competes with
continuous capture and with thermal throttling over hours. The test therefore asserts **> 2×**,
not > 1× — parity in the lab would mean falling behind in practice. 5.29× clears that.

The assertion is enforced on hardware and only logged on an emulator (`isEmulator()`), because
the emulator reports `SSE3 / SSSE3` with no AVX2 and ggml is heavily SIMD-bound; its 0.48×
figure — Tiny, not Base — never meant anything.

---

## Not yet verified

**Audio → Devanagari, end to end.** `d_transcribesKnownSampleCorrectly` uses `jfk.wav`, which
is English, and the Devanagari tests seed *text* directly into Room — they exercise the
tokeniser, not the recogniser. `IndicSpeechInstrumentedTest` closes this by synthesising Hindi
and Marathi with the phone's own TTS and feeding it to whisper (the Pixel reports
`availability=1` for both `hi_IN` and `mr_IN`), and `AcousticCaptureInstrumentedTest` plays
speech out of the loudspeaker and records it through the phone's own microphone. Both are
written and compile; **neither has completed a run**, because the phone dropped off USB again
partway through. Until they pass, "multilingual Hindi/Marathi STT" rests on the model card, not
on a measurement.

**Real far-field human speech.** Even once those pass, TTS is clean, close-miked, studio-grade
audio. It cannot tell you how the model handles an accented speaker across a room with a fan
running. The only test for that is using it.

**Reboot resume, mic preemption by an incoming call, and OEM battery-killer behaviour** are
implemented and reasoned about, but need a handset living a normal day to be proven.
