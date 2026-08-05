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

## Not yet verified

**The app is not currently installed on the phone.** Gradle's `connectedAndroidTest` uninstalls
both APKs when it finishes, including after a failure, so the last test run left the device
empty — no app, no downloaded model, no permission grants, no battery-optimisation exemption.
Re-running `scripts\verify-on-device.ps1` restores all of it.

**The 11 PM chain on Android 17 — partially verified, and no longer manually testable.**

What *is* confirmed on the Pixel (API 37):

```
tag=*walarm*:com.mandar.echo/.summary.SummaryAlarmReceiver
type=RTC_WAKEUP origWhen=2026-08-05 23:00:00.000 window=0 exactAllowReason=policy_permission
```

`window=0` is a true exact alarm and `exactAllowReason=policy_permission` confirms
`USE_EXACT_ALARM` was auto-granted. The app is also in standby bucket **5 (exempted)** and on
the deviceidle whitelist, so neither App Standby nor Doze should defer it.

What could **not** be re-tested: firing the receiver by hand. `SummaryAlarmReceiver` is
`exported="false"`, and on API 37 an explicit `am broadcast` from the shell to a non-exported
component is enqueued and then silently dropped — the receiver's first line never logs. This
worked on the API 36 emulator (§5) and does not any more. It is a testing limitation rather
than a defect: the real alarm is delivered by AlarmManager to the app's own PendingIntent,
which is not subject to that check. The receiver was deliberately **not** exported to make it
testable, because an exported receiver that triggers transcription and notification is a
security hole in an app that records people.

So the still-unverified part on API 37 is narrow but real: the `dataSync` foreground-service
promotion used when the summary fires while **not** recording. Recording at the time makes the
question moot, and it is the common case at 11 PM that it is not.

**A real capture run on the phone.** Section 4's live run was on the emulator, against digital
silence. Sample-exact rotation and post-transcript deletion are proven; hearing anything is not.

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

**Real far-field human speech, scored.** The 445-word chunk above has not been checked against
what was actually said. TTS fixtures are clean, close-miked and studio-grade; they cannot tell
you how the model handles an accented speaker across a room with a fan running. The only test
for that is a human reading a transcript of their own day.

**Reboot resume, mic preemption by an incoming call, and OEM battery-killer behaviour** are
implemented and reasoned about, but need a handset living a normal day to be proven.
