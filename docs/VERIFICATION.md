# Verification record

What was actually run, and what it proved. Dated 2026-08-05, with the transcription-queue
rewrite (`6639bc0`) appended 2026-08-06.

Sections 1–9 are the pre-rewrite record and are unchanged except where the rewrite invalidated
them, which is marked inline. Sections 10 and 11 cover the cloud backend.

**Every queue-rewrite claim in this document is either a code read or a figure recorded by the
commit that made the change. No part of the rewrite has been observed running on the phone**
— see [Not yet verified](#not-yet-verified), which is the longest section here for that
reason. Nothing below was re-run in the session that wrote this update; where a number is
carried over from a commit message it says so.

Every **queue-rewrite** code read here is against the committed Echo tree at `6639bc0`. The
working tree held uncommitted, incomplete follow-up work while this was being written, and none
of it is described here as having landed. (§11 reads a different repo at a different commit and
says so.)

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

Measured before `6639bc0`, which touched `app/build.gradle.kts` and added ~2,400 lines of
Kotlin but no native code. The release APK has not been rebuilt or re-measured since.

## 2. JVM unit tests — 89

Recorded green at `6639bc0`; **not re-run in the session that wrote this document.** Counted
by `@Test` method, the suite is:

| File | Tests | Covers |
|---|---|---|
| `ChunkMathTest` | 9 | the "every minute is recorded" guarantee — split sizes always sum to the buffer length, walking a full chunk one buffer at a time, including a chunk size that is not a multiple of the buffer size and a buffer spanning several chunks |
| `WavWriterTest` | 7 | header fields, size patching on close, value-exact round trip, offset writes, idempotent close, recovery of a chunk truncated by a crash |
| `VoiceActivityDetectorTest` | 5 | digital silence and quiet room tone reject; sustained speech over a quiet floor accepts |
| `VoicedGateTest` | 9 | the compacted voiced stream and `originalMs` — mapping a backend timestamp back through the gate to wall-clock |
| `AudioGainTest` | 7 | 99.5th-percentile headroom (§8) |
| `SummaryTest` | 27 | tokenisation and stopwords across English *and* Devanagari, danda sentence splitting, TF-IDF distinctiveness, TextRank ordering, name heuristics, next-11-PM arithmetic, duration/language/percentage rendering |
| **`BatchProtocolTest`** | **24** | the wire contract, as pure functions over `(status, body)` with no socket: every submit and poll outcome, classified transient-or-permanent exactly once |
| `JsonRealityCheckTest` | 1 | `org.json` behaviour the protocol relies on, asserted rather than assumed |

`BatchProtocolTest` is the part worth calling out. The original hang was that `poll()` used
`isNotBlank()` as a readiness test, so `200 {"status":"completed","transcript":""}` — an
ordinary success — was unrepresentable and the client waited forever for a state that had
already happened. The table tests exist so that outcome has a name.

**What these tests cannot prove:** they run against the protocol, not against a socket and not
against the deployed service. The queue's own state machine — claim, park, lease recovery,
`cloud_jobs` resume — has no JVM test at all, because it needs a database.

## 3. Instrumented tests — 11 of 16 passing on the Pixel 9

Eleven run against the release variant on the physical phone (`Starting 11 tests on
Pixel 9 - 17` → `0 failed`), and previously on the emulator. Note the OS: **API 37, one level
above the `compileSdk` of 36**, so the suite is also evidence that nothing breaks on the next
Android release.

The other five are `d2_baseModelSustainsRealtimeThroughput` (passing — see §6) and the four
listed under [Not yet verified](#not-yet-verified), which are written and compile but have not
completed a run.

> **These tests are destructive.** `SummaryEngineInstrumentedTest` and
> `AcousticCaptureInstrumentedTest` both call `deleteAll()` on the real app database, and
> Gradle **uninstalls the app afterwards** — which also wipes app-private storage, including
> the downloaded model, and drops the runtime permission grants and the battery-optimisation
> exemption. Always re-run `verify-on-device.ps1` after a test run, not a bare `adb install`.
>
> `verify-on-device.ps1` skips the suite unless `-RunTests` is passed explicitly. Never run it
> on a phone holding recordings you want to keep.
>
> Note that `-RunTests` currently **fails the build**: the suite now contains four tests
> (`IndicSpeechInstrumentedTest` ×3, `AcousticCaptureInstrumentedTest` ×1) that have never
> completed a run. Target the eleven known-good ones with
> `-Pandroid.testInstrumentationRunnerArguments.class=...` until those pass.

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

> **These eleven results predate the queue rewrite (`6639bc0`) and four of them are now
> stale.** `processNext()` used to return true after any claim; it now returns true only on a
> *terminal* outcome and false on a park, so the asserts at
> `WhisperPipelineInstrumentedTest.kt:220/271/300` and `AcousticCaptureInstrumentedTest.kt:259`
> no longer hold. Independently, the claim query now takes the **oldest** claimable chunk, not
> the test's freshly inserted one, so on a device with a backlog the assert can fail against a
> chunk the test never created. `f_pipelineTranscribesThenDeletesAudio` additionally fails on
> content if the cloud backend is selected: the default language is `auto`, which posts as
> `hi-IN`, and English `jfk.wav` through IndicConformer-as-Hindi cannot contain "country".
>
> None of this was re-run. Fixing the tests is pending work, not a verified result.

## 4. Live run — **emulator only**

Everything in this section and the next ran on the **Android 16 x86_64 emulator**, while the
phone was unreachable over ADB. Neither has been repeated on the Pixel 9 — see
[Not yet verified](#not-yet-verified).

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

## 5. The 11 PM chain, fired for real — **emulator only**

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

The next three were the live symptom "18 chunks queued, 0 words written", and none of them was
visible to any test. They are what `6639bc0` exists to fix; the fixes themselves are code
reads, not device runs — see [Not yet verified](#not-yet-verified).

12. **An empty transcript was unrepresentable.** `poll()` used `isNotBlank()` as a readiness
    test. The server answers a silent job with `200 {"status":"completed","transcript":""}`,
    an ordinary success — so the client waited forever for a state that had already happened.
    Readiness is now *key presence*, and every wire outcome is a named case classified
    transient-or-permanent exactly once, with 24 table tests.
13. **Every server-side failure logged an empty reason.** The client read `error`; the server
    writes `error_message` on the status path.
14. **`en-IN` was silently transcribed as Malayalam.** It is absent from the server's
    `LANG_MAP` and the batch path does not validate the code, so it coerces to `ml`. The
    failure looked like success: HTTP 200, non-empty text, stored and labelled English.
    English is now never sent to the server.
15. **A cold server permanently downgraded a chunk.** Waiting on something external counted as
    a failed attempt, so three network blips exhausted a chunk's retries and handed it to an
    engine measured at 0.00 word recall on Marathi. Waiting is now a *park*, which never
    touches `attempts`.

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

## 7. Which model? Measured, not assumed

`ModelComparisonInstrumentedTest` runs the same four fixtures through each installed model and
reports word recall against a known reference. Fixtures are 16 kHz clips synthesised by Google
Cloud TTS, committed to `androidTest/assets`, so the numbers are reproducible.

| model | Hindi | Marathi | code-switched | English | realtime (emulator) |
|---|---|---|---|---|---|
| Base | 0.23 | 0.00 | 0.08 | 1.00 | 0.15× |
| **Small** | **0.54** | **0.13** | **0.38** | 1.00 | 0.06× |

Small more than doubles Hindi recall and is roughly five times better on code-switched speech,
for about 2.5× the compute. Both are perfect on English, so the English fixture proves only that
nothing regressed.

**The recall figures understate Small.** Its code-switched output was

> कल का सेंसर कलबरेशिन अभी पेंटिंग है, मैं एवनिंग दक अबड़ेट बहेज दुंगा

against a reference of "कल का sensor calibration अभी pending है, मैं evening तक update भेज दूंगा।"
That is very nearly the sentence — but whisper transliterates the English words into Devanagari
while the reference keeps them in Latin, so exact word matching scores them as misses. Read the
transcripts in the log, not just the number.

The realtime column is from an x86_64 emulator without AVX2 and is meaningful only as a *ratio*
between the two models. On the Pixel 9, Base measured 5.29× on clean English and 0.4× on
far-field Marathi before the ladder was capped — the spread between those two, on one model, is
larger than the spread between models.

---

## 8. Level normalisation, measured on the phone

The clearest single result of the whole exercise. Two chunks of the same user's far-field
speech, same device, same model (Small), same build except for how headroom is measured:

| | `gain=1.0x` (true peak) | `gain=3.4x` (99.5th percentile) |
|---|---|---|
| raw segments from the decoder | 421 | **118** |
| of those, repeats | 400 (95%) | **82 (69%)** |
| segments kept | 21 | **36** |
| words | 68 | **445** |
| realtime factor | 0.9× | 0.5× |

Both chunks measured `rms ≈ 0.024`, a quarter of the level whisper expects. In the first, a
single loud transient had already reached full scale, so the true-peak calculation permitted no
gain at all and the decoder worked on audio far below its training distribution — where it
falls back on its priors and loops. Measuring headroom at the 99.5th percentile instead cut the
decoder's discarded output by **72%** and produced **6.5× more transcript**.

The realtime factor moved the wrong way for the right reason: at 68 words the model was
mostly looping and bailing, at 445 words it is actually decoding speech. Throughput bought
quality here, and that is the correct trade — but see below, because 0.5× is not sustainable.

---

## 9. Silero VAD: implemented, measured, and left switched off

whisper.cpp v1.9.2 ships Silero VAD, and it looked like the obvious fix for Echo's energy gate
passing 527.9 s of a 600 s chunk. It is wired end to end — 885 KB model, downloaded once,
`params.vad` set through the JNI — and it makes Hindi **worse**:

| | energy gate only | + Silero VAD |
|---|---|---|
| Hindi recall | **0.385** | 0.231 |

The failure mode is legible in the output:

> अच दो पहल कु मीटिं ते चो परे में ते लगत के बारे में **ते लगता है।**

The Hindi initial prompt ends `…आपको क्या लगता है।` — the model is completing the prompt rather
than transcribing. Silero trims the audio whisper sees; on a short segment the carried prompt
starts to outweigh what remains, and `carry_initial_prompt` re-applies it to every window. The
two features fight.

It is left off. The plumbing stays because the idea is still right for long noisy chunks — the
fixtures here are 4-second clips that are already 100% speech, which is the case Silero can only
hurt. Anyone re-enabling it should set `engine.vadModelPath`, and should expect to disable
`carry_initial_prompt` at the same time.

**The general lesson, since it cost a full cycle to learn:** every quality change in this
pipeline was verified against fixtures with known references before shipping, and this is the
one that failed. Reasoning about it would have shipped a regression.

---

## 10. The cloud backend: which model, measured

Word recall against known references, same fixtures, same day. This is the measurement that
justifies the whole park-instead-of-fall-back policy in
[SYSTEM_DESIGN §5.3](SYSTEM_DESIGN.md#53-why-parking-exists):

| | Hindi | Marathi | code-switched |
|---|---|---|---|
| Whisper Base (on-device default) | 0.23 | **0.00** | 0.08 |
| Whisper Small (on-device) | 0.54 | 0.13 | 0.38 |
| **IndicConformer-600M (server)** | **1.00** | **1.00** | 0.67\* |

\* Scoring artefact, not a model failure: IndicConformer transliterates "sensor" and "pending"
into Devanagari while the reference keeps them in Latin. The sentence is right. Chirp 2 does
the same thing — and turns उद्या into उदय on Marathi, which IndicConformer does not.

Whisper is an English model that tolerates Hindi. IndicConformer-600M is AI4Bharat's, built
for 22 Indian languages. **0.00 on Marathi is not "worse", it is nothing** — a day of Marathi
through Whisper produces a transcript that is not a transcript. No amount of gate or gain
tuning closes a gap that size; the model was the limit, not the pipeline.

Verified live against the deployed service at `4a66975`: Marathi and Hindi both returned word
for word in ~2.5 s round trip, and an unauthenticated request was rejected 403.

## 11. The wire contract, checked against the server's source

`vexyl-stt` is the user's own server, so its source can be read rather than probed. Eleven
client-side assumptions were checked line by line against
`C:\Users\Mandar\vexyl-stt\vexyl_stt_server.py` at commit `4f674d9`, and all eleven hold:

- `POST /batch/transcribe` returns **201** with `job_id` and `audio_duration`.
- `GET /batch/status/{id}` returns 200 for any known job with `status` in exactly
  `{queued, processing, completed, failed}`, and 404 for an unknown one.
- A COMPLETED job **always carries the `transcript` key**, with a `str` value that may be `""`.
  Missing-key and `null` are both unreachable in the server. This is what makes key-presence
  the correct readiness test and the original hang unrepresentable.
- A failure reason is written to `error_message` on the status path and `error` on submit
  refusals. The client reads each in the right place. (`error_message` is always the literal
  `"Transcription failed"`, so it carries no diagnostic content.)
- Jobs live in an in-process dict with no persistence, swept ~3600–3900 s after `completed_at`.
  The client's 1-hour `JobGone` threshold is the right order.
- Caps are 300 s and 25 MB. A 120 s piece is ~3.84 MB — 6.5× under.
- One inference at a time, behind a global lock, with a single batch worker draining a single
  queue. The client's one-outstanding-job rule is accurate.
- `/health` is served **before** the API-key check, and returns the hardcoded model name the
  client fingerprints. It also does not bind until the model is loaded and warmed, so a green
  `/health` means more than "the container is listening".
- `LANG_MAP` has 14 languages and no English entry, and the batch path does **not** validate
  `language_code` — it coerces anything unmapped to Malayalam. An `en-IN` upload returns
  HTTP 200 with plausible Malayalam-script text. Keeping English off the client's supported
  set is load-bearing, not tidiness.
- A missing `Content-Length` yields `400 {"error": "Missing 'file' field..."}`, traced end to
  end. The ban on `setChunkedStreamingMode` prevents a silent 100% upload failure behind a
  misleading error.
- 403 on the status path is plain text; on the POST path it is JSON. Classifying by status
  code on both is the only shape-independent way to be right.

**This is a source read, not a probe.** Every item above is conditional on the deployed Cloud
Run revision matching that checkout, which was **not** checked. The Cloud Run flags the client
comments cite (`--startup-probe-period=10 × --startup-probe-failure-threshold=12` = the 120 s
wake budget, `--min-instances=0`, `--concurrency=50`, `--timeout=3600`) come from an
**uncommitted** `deploy.sh`.

Three things the read turned up that the client does not account for:

1. **A transient server-side inference failure is made permanent.** The server writes FAILED
   from a blanket `except Exception` with a fixed string — OOM, a torch allocation failure and
   a genuinely malformed input are indistinguishable. The client marks the piece REJECTED
   forever. A whole-chunk rejection falls through to Whisper; a **per-piece** rejection is
   silently dropped and reaches no engine at all, leaving a hole that pins the chunk's 19 MB
   with nothing that ever redoes or releases it.
2. **The 429 threshold is dead code for this client.** It fires at 1000 pending jobs, and each
   queued job pins 7.68 MB of float32 PCM on a 4 GiB instance. The server would OOM around 999.
   The client's one-outstanding-job rule is what protects the *server*, not merely itself.
3. **The multipart parser strips a character set, not a delimiter** — `part.strip(b"\r\n")`
   eats every trailing byte in `{0x0D, 0x0A}`. Measured impact: `sf.read` accepts the short
   `data` chunk without error and returns one fewer frame, so ~62 µs off the end of a 120 s
   piece. Real bug, server-side fix, negligible. Do not paper over it with a client-side pad.

---

## Not yet verified

**The app is not currently installed on the phone.** Gradle's `connectedAndroidTest` uninstalls
both APKs when it finishes, including after a failure, so the last test run left the device
empty — no app, no downloaded model, no permission grants, no battery-optimisation exemption.
Re-running `scripts\verify-on-device.ps1` restores all of it.

### The transcription queue rewrite has never run on hardware

`6639bc0` rebuilt the queue into a durable state machine. **Not one line of it has executed on
a phone.** What exists is 89 JVM unit tests (§2), a line-by-line read of the migration SQL
against the schema Room generates, and a line-by-line read of the server (§11). Specifically
unobserved:

- **The park/resume cycle.** A chunk going `PENDING → TRANSCRIBING → park → PENDING` and being
  re-claimed later has never happened outside a code read. Nor has the claim ordering that puts
  a chunk with an outstanding server job at the head of the queue.
- **`cloud_jobs` surviving a park.** The entire point of making piece state durable is that
  resuming continues the server's job instead of re-uploading megabytes it still holds. The
  resume path (`streamFingerprint` matches → reuse rows) has never been exercised against a
  real server round.
- **The lease.** `LEASE_TIMEOUT_MS` (45 min), `requeueStale`, and `LeaseLostException` rolling
  a commit back have no test and no observed occurrence. The lease timeout is also *shorter*
  than a legitimate worst-case cloud round (five 120 s pieces × a 16-minute poll ceiling each,
  plus a 120 s wake probe), which is harmless only because one coroutine runs the sweep and the
  claim as sequential statements.
- **`MIGRATION_1_2` against a real populated database.** The SQL was verified by reading it
  against Room's generated schema — `exportSchema` is off, so nothing validates it at build
  time — but it has not been run over a database with the user's actual rows in it. There is
  deliberately no `fallbackToDestructiveMigration`, so a mistake here is a crash, not a wipe.
- **The 1.00/1.00 IndicConformer figures are from fixtures posted to the deployed service, not
  from Echo's own queue end to end.** No transcript has been produced by
  `TranscriptionPipeline → CloudTranscriber → server → segments in Room` on the phone.
- **Whether the deployed Cloud Run revision matches the `vexyl-stt` checkout §11 was read
  against.** Every server claim is conditional on that.
- **`FIRST_POLL_FRACTION = 0.35` and `POLL_CEILING_FACTOR = 8`** rest on "IndicConformer runs
  at roughly 2–3× realtime on a 2 vCPU CPU-only instance", which is stated as fact in a comment
  and is benchmarked nowhere. If the true factor is worse than 8×, the symptom is a park at the
  ceiling with `progressed = true` — benign but slow. This needs a measurement, not a source
  read.

### Known-broken as read at `6639bc0`

These were found by reading the committed tree after the rewrite, and each is recorded in
[SYSTEM_DESIGN §5](SYSTEM_DESIGN.md#5-transcription-queue) with the mechanism that does or does
not enforce it. None is speculative. None had been fixed in a committed state when this was
written, and **none has been observed on hardware in either direction** — so a fix landing
after this date does not update this list by itself. Check the tree before trusting an entry.

1. **The leftover sweep can delete audio the user asked to keep**, on every pipeline start.
   A startup sweep sees only rows — no settings, no memory of what the committing run decided
   — so its predicate has to match a decision that was *recorded*. `audioHold IS NULL` records
   nothing, and it is character-for-character the state a cleanly transcribed chunk is left in
   when "keep audio after transcription" is on. Deterministic, destroys data, new in this
   commit. The fix shape is a positive *cleared for release* marker written in the same
   transaction as the terminal status, so the sweep only finishes a job it can see was started.
2. **A held chunk is invisible and permanent.** `requeueDegraded` / `degradedCount` — the
   queries written to find and redo a held chunk — have no callers, so nothing ever redeems
   one. Their predicate also keys on `transcriptSource = 'device'`, a proxy that cannot see a
   *cloud* transcript with a hole, which is the commonest hold; `coveredMs`, the column that
   records exactly that evidence, appears in no `WHERE` clause anywhere. Because nothing
   releases held bytes, `underPressure` is a one-way latch: past 1 GB the cloud is bypassed
   permanently and the app silently reverts to the 0.00-Marathi engine.
3. **Summaries lie about a parked day.** `SummaryEngine` buckets only `DONE`/`SILENT`/`FAILED`
   while counting every chunk, so a day recorded against a cold server reports hours captured,
   "no speech detected", "0 words transcribed" and no failure line — and notifies the user
   with it. `summaries.provisional` and `ChunkDao.unsettledBetween` were added for exactly this
   and are wired to nothing.
4. **A FAILED chunk whose WAV is gone can never be retired** and retries forever, resetting
   `attempts` each time, so the "Retry N failed" button appears to do nothing.
   `discardFailedWithoutAudio` matches `filePath IS NULL`, which that path never sets.
5. **A SUBMITTED `cloud_jobs` row can outlive its chunk**, and one orphan is enough to park
   every other chunk's cloud path for the life of the install. Two independent gaps have to
   hold for it: the rows are not settled on *every* terminal edge (`clearForChunk` is missing
   from `failChunk` and from the catch-all), and the gate query asks only "is any row
   SUBMITTED" rather than "is any row SUBMITTED for a chunk somebody will come back for".
   Closing either one alone would defuse it.

### The 11 PM chain has never been observed firing on Android 17

The chain fired end to end exactly once, on the **Android 16 (API 36) emulator** (§5). It has
not been seen on the Pixel 9 (API 37), and on API 37 it can no longer be provoked by hand.

What *is* confirmed on the Pixel (API 37):

```
tag=*walarm*:com.mandar.echo/.summary.SummaryAlarmReceiver
type=RTC_WAKEUP origWhen=2026-08-05 23:00:00.000 window=0 exactAllowReason=policy_permission
```

`window=0` is a true exact alarm and `exactAllowReason=policy_permission` confirms
`USE_EXACT_ALARM` was auto-granted. The app is also in standby bucket **5 (exempted)** and on
the deviceidle whitelist, so neither App Standby nor Doze should defer it.

That is the alarm being *armed*. Nothing above shows it being *delivered*, the receiver
running, the service promoting, or a summary being written — on this OS.

**The receiver cannot be hand-triggered on API 37.** `SummaryAlarmReceiver` is
`exported="false"`, and on API 37 an explicit `am broadcast` from the shell to a non-exported
component is enqueued and then silently dropped — the receiver's first line never logs. This
worked on the API 36 emulator (§5) and does not any more. It is a testing limitation rather
than a defect: the real alarm is delivered by AlarmManager to the app's own PendingIntent,
which is not subject to that check. The receiver was deliberately **not** exported, because an
exported receiver that triggers transcription and notification is a security hole in an app
that records people — so this will not be made testable by exporting it.

The only remaining way to verify the chain on API 37 is to leave the app installed and running
across a real 23:00. That has not been done.

Two parts are specifically unverified on API 37 as a result:

- The `dataSync` foreground-service promotion used when the summary fires while **not**
  recording. Recording at the time makes the question moot, and it is the common case at 11 PM
  that it is not.
- Whether the summary the chain produces is *honest*. See "Known-broken" item 3 above — a day
  with parked chunks currently produces a summary that says nothing was transcribed. That
  headline is what the 11 PM notification shows.

**A real capture run on the phone.** Section 4's live run was on the emulator, against digital
silence. Sample-exact rotation and post-transcript deletion are proven *on that build*;
hearing anything is not, and the deletion logic has since been rewritten around `audioHold`
and has not been re-observed anywhere.

**Audio → Devanagari, end to end.** `d_transcribesKnownSampleCorrectly` uses `jfk.wav`, which
is English, and the Devanagari tests seed *text* directly into Room — they exercise the
tokeniser, not the recogniser. `IndicSpeechInstrumentedTest` closes this by synthesising Hindi
and Marathi with the phone's own TTS and feeding it to whisper (the Pixel reports
`availability=1` for both `hi_IN` and `mr_IN`), and `AcousticCaptureInstrumentedTest` plays
speech out of the loudspeaker and records it through the phone's own microphone. Both are
written and compile; **neither has completed a run**, because the phone dropped off USB again
partway through. Until they pass, "multilingual Hindi/Marathi STT" rests on the model card, not
on a measurement.

**Whether Small can keep up in a noisy room — it currently cannot.** Measured at 0.5× realtime
on a chunk that was 88% voiced (527.9 s of 600 s). Over a waking day that backlog never clears.
Two things make this less alarming than it sounds and one makes it worse:

- 88% voiced is an unusually live environment. A quiet evening gates down to a fraction of that
  and Small keeps up comfortably — the binding number is *voiced seconds per day*, not chunk
  duration.
- Audio is retained until transcribed, so a backlog is lag rather than loss, and it drains
  whenever the room goes quiet.
- But an 88%-voiced chunk suggests the VAD gate is too permissive, not that the room is that
  busy. Tightening it would cut compute *and* quality-harming noise at once, and is the single
  highest-value change left. Base at roughly 2.5× Small's speed is the fallback that keeps up
  today, at about half the Hindi accuracy.

The cloud backend changes this arithmetic and has not been measured against it. On CLOUD the
phone's constraint stops being CPU and becomes upload bandwidth plus a server that runs one
job at a time — 88% of 600 s is five 120 s pieces, serialised, per chunk. Whether that drains
a waking day is unknown; nothing has measured it.

**Real far-field human speech, scored.** The 445-word chunk above has not been checked against
what was actually said. TTS fixtures are clean, close-miked and studio-grade; they cannot tell
you how the model handles an accented speaker across a room with a fan running. The only test
for that is a human reading a transcript of their own day.

**Reboot resume, mic preemption by an incoming call, and OEM battery-killer behaviour** are
implemented and reasoned about, but need a handset living a normal day to be proven.
