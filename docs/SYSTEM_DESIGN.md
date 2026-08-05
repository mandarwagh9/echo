# Echo — System Design

A 24/7 ambient audio journal for Android. Records continuously, transcribes on-device in
10-minute chunks, deletes the audio once the transcript is durable, and produces a summary
of your day at 11:00 PM.

Target: **one device** (the developer's own phone). Prototype quality, production discipline.

---

## 1. Product requirements (verbatim, restated)

| # | Requirement | Design response |
|---|---|---|
| R1 | Records 24/7 | Foreground service, `microphone` type, persistent notification |
| R2 | 10-minute chunks | Sample-exact rotation at 9,600,000 samples (600 s × 16 kHz) |
| R3 | Transcribe each chunk | whisper.cpp via JNI, arm64-v8a |
| R4 | Delete audio after transcription | WAV unlinked only after transcript is committed to DB |
| R5 | **Every minute is recorded** | Mic read decoupled from disk + STT by a bounded handoff queue |
| R6 | Transcription in background | Dedicated low-priority executor, never on the recorder thread |
| R7 | Offline, on-device STT | whisper.cpp, no network at inference time |
| R8 | Multilingual EN / HI / MR | Whisper multilingual models (`en`, `hi`, `mr` all natively supported) |
| R9 | Daily summary at 11 PM | Exact alarm → summary worker → reschedule |
| R10 | Black & white UI | Monochrome Compose design system |

---

## 2. Pipeline

```
                    ┌──────────────────────────────────────────┐
                    │  RecordingService (foreground, mic type)  │
                    └──────────────────────────────────────────┘
                                       │
   ┌───────────────────────────────────┼───────────────────────────────────┐
   │                                   │                                   │
┌──▼─────────────────┐   handoff  ┌────▼──────────────┐            ┌───────▼────────────┐
│  Recorder thread   │  queue     │  Writer thread    │            │ Transcriber pool   │
│  URGENT_AUDIO prio │ ─────────▶ │  WAV I/O          │            │ BACKGROUND prio    │
│                    │  (bounded, │  + chunk rotation │            │                    │
│ AudioRecord.read() │   64 buf)  │  + header patch   │            │ whisper.cpp JNI    │
│ 16 kHz mono PCM16  │            └────┬──────────────┘            └───────┬────────────┘
│ NEVER blocks       │                 │                                   │
└────────────────────┘                 │ chunk closed                      │
                                       ▼                                   │
                              ┌──────────────────┐   claim oldest PENDING  │
                              │  Room: chunks    │ ◀───────────────────────┘
                              │  status=PENDING  │                         │
                              └──────────────────┘                         ▼
                                                              ┌─────────────────────────┐
                                                              │ 1. segments -> Room     │
                                                              │ 2. status = DONE  (txn) │
                                                              │ 3. THEN delete WAV      │
                                                              └─────────────────────────┘
                                                                          │
                                       ┌──────────────────────────────────┘
                                       ▼
                      ┌────────────────────────────────┐
                      │ AlarmManager @ 23:00 exact     │
                      │  → DailySummaryWorker          │
                      │  → summaries table → UI        │
                      └────────────────────────────────┘
```

### 2.1 Why three threads, not one

R5 ("every minute is recorded") is the hardest constraint in the spec. `AudioRecord` holds a
finite internal ring buffer; if nobody drains it, it **silently overwrites** — you get no
exception, just missing audio. So the read loop must never do anything that can block:

- **Recorder thread** does exactly one thing: `read()` into a pooled `ShortArray`, then
  `offer()` to the writer queue. No file I/O, no DB, no allocation in steady state.
- **Writer thread** owns all file I/O. A slow flush stalls the writer, not the mic.
- **Transcriber** is a separate executor entirely. whisper is CPU-heavy (4 threads); running
  it anywhere near the recorder thread would cause dropouts.

If the writer queue ever saturates (disk stall), we drop the *newest* buffer and increment a
counter surfaced in the UI — an honest, visible gap rather than silent corruption.

### 2.2 Chunk rotation is sample-exact, not timer-based

`AudioRecord` is **never stopped or restarted** during rotation — stopping it is what causes
gaps. The writer counts samples; at exactly 9,600,000 it splits the *current* buffer across
the boundary, patches the closing file's WAV header, and opens the next file. Cost is a
`close()` + `open()` on the writer thread (~1 ms), fully absorbed by the handoff queue.

Consequence: chunk N+1 begins on the very next sample after chunk N. No gap, by construction.

---

## 3. Audio format

| Property | Value | Rationale |
|---|---|---|
| Sample rate | 16 000 Hz | Whisper's native rate — zero resampling |
| Channels | 1 (mono) | Whisper is mono |
| Encoding | PCM 16-bit signed | Direct `AudioRecord` output |
| Source | `MIC` | See below — `VOICE_RECOGNITION` is the wrong choice here |
| Container | WAV (44-byte header) | Trivially seekable, patchable, whisper-friendly |

**Chunk size on disk:** 600 s × 16 000 × 2 B = **19.2 MB**. With transcription keeping up,
steady-state disk use is 2–3 chunks ≈ 60 MB.

### 3.1 Why `MIC` and not `VOICE_RECOGNITION`

`VOICE_RECOGNITION` looks like the obvious choice and is the wrong one. It is tuned for a
phone held to your face: it applies near-field AGC and noise suppression that actively
suppresses the quiet, reverberant, off-axis speech that makes up ambient room capture. It is
also the source most aggressively yielded to the assistant and dialer on many OEM builds —
precisely the wrong property for a recorder that must hold the mic all day.

`MIC` is the flat, unprocessed ambient source. It is exposed as a setting so both can be
compared on real hardware, but `MIC` is the default and the intended one.

### 3.2 Reading a chunk back

A 10-minute chunk is 9.6M samples. Reading it by slurping the file and then converting would
hold a 19 MB byte array and a 38 MB float array live simultaneously, and JNI may copy the
float array again on top of that — enough to OOM a mid-range phone. `WavWriter.readAsFloats`
therefore streams in 64 KB blocks, so only the destination array is ever large.

---

## 4. Data model (Room)

```kotlin
@Entity chunks
  id            Long PK autoincrement
  startedAt     Long        // epoch ms of first sample
  endedAt       Long?       // epoch ms of last sample
  filePath      String?     // null once audio deleted
  sampleCount   Long
  status        RECORDING | PENDING | TRANSCRIBING | DONE | FAILED | DISCARDED
  attempts      Int
  audioDeleted  Boolean
  error         String?
  transcribeMs  Long?       // wall-clock cost, drives the realtime-factor gauge

@Entity segments                 // one row per whisper segment
  id       Long PK
  chunkId  Long FK -> chunks(id) ON DELETE CASCADE   [indexed]
  startMs  Long                  // absolute epoch ms
  endMs    Long
  text     String
  language String               // "en" | "hi" | "mr"

@Fts4 segments_fts(text)        // full-text search over the day

@Entity summaries
  dayEpochDay  Long PK          // LocalDate.toEpochDay()
  generatedAt  Long
  headline     String
  bodyMarkdown String
  statsJson    String
```

**Ordering invariant (R4):** segments insert + `status = DONE` happen in **one transaction**.
The WAV is deleted only after that transaction commits. A crash mid-transcription therefore
leaves audio on disk and the chunk `PENDING` — it retries. We never delete audio we cannot
prove was transcribed.

---

## 5. Failure modes and their handling

| Failure | Detection | Response |
|---|---|---|
| Mic preempted (call, assistant, other app) | `AudioRecordingCallback` reports silenced, or `read()` returns error | Close current chunk cleanly, retry acquisition with backoff (1s→30s), post "paused" notification |
| Phone reboots | Service gone | `BOOT_COMPLETED` **cannot** legally start a mic FGS on Android 14+. We post a high-priority "Tap to resume" notification instead. Honest, and the only compliant option. |
| Disk fills | Free space < 1 GB | Pause recording, notify. Auto-resume at > 1.5 GB (hysteresis) |
| Transcription falls behind | pending chunks > 12 (≈2 h) | Warn in UI + notification; keep recording (R1 wins over disk) |
| Transcription fails | Exception / empty result | attempts++; retry ×3; then `FAILED` and **keep the audio** so nothing is lost |
| OEM battery killer | Service death | `START_STICKY` + restart alarm + guide user to "unrestricted battery" in onboarding |
| Model missing | No file at path | Recording continues; chunks queue as PENDING until a model is installed |
| Silence (most of a real day) | Adaptive energy VAD before whisper | Chunk marked SILENT, whisper skipped entirely, audio deleted. Saves battery and stops whisper hallucinating text from room tone |
| `startForeground` refused | Exception on promotion | Log and `stopSelf()` rather than crash-loop on a system-initiated restart |

**Design stance:** recording is more important than transcription; transcription is more
important than disk. Audio is never deleted on an unproven path.

---

## 6. STT engine

**whisper.cpp**, vendored and built with NDK 27 for `arm64-v8a` only (keeps APK and build
footprint small on a disk-constrained machine).

Whisper is the correct choice for this spec because Marathi (`mr`) is a natively supported
language alongside Hindi (`hi`) and English (`en`). Vosk has no usable Marathi model; the
Android `SpeechRecognizer` is neither reliably offline nor continuous.

| Model | Size (q5_1) | Speed on modern arm64 | Use |
|---|---|---|---|
| tiny | ~31 MB | fastest | fallback / low-end |
| **base** | **~57 MB** | ~5–10× realtime | **default** |
| small | ~190 MB | ~1.5–3× realtime | best quality, must stay > 1× |

Downloaded in-app from HuggingFace on first run (the only network the app ever needs).
Inference itself is fully offline.

**Language handling:** default `auto` (per-chunk detection) to survive EN/HI/MR
code-switching, with a manual override in Settings. `translate = false` — we keep the
original language.

Threads: `min(4, availableProcessors - 2)`, floor 2 — leaves headroom for the recorder.

---

## 7. Daily summary at 11:00 PM

`WorkManager` periodic work has a ~15-minute flex window and will not hit 11 PM reliably.
Instead:

```
AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, next 23:00)
   → SummaryAlarmReceiver
   → RecordingService (ACTION_GENERATE_SUMMARY)
   → generate + persist summary + notify
   → schedule next 23:00
```

The work runs **on the foreground service, not in WorkManager**. Enqueuing a job at 23:00
would let Doze defer it for hours; the service is already alive when recording, so handing the
work to it sidesteps that entirely.

Two subtleties this design has to handle:

- **Promotion type.** If recording is *off* when the alarm fires, the service cannot promote
  itself with the `microphone` foreground type — mic is a while-in-use type and a background
  start is refused on Android 14+. That path promotes as `dataSync` instead, and calls
  `stopSelf()` once the summary is written so it does not linger showing a notification that
  implies it is recording.
- **Self-healing.** The alarm is re-armed on app open, on boot, and after every run, so a
  dropped or cancelled alarm recovers on its own.

`USE_EXACT_ALARM` is declared rather than `SCHEDULE_EXACT_ALARM` alone: it is auto-granted and
needs no runtime request flow. It is Play-policy restricted to alarm/calendar apps, which is
irrelevant for an app that is only ever sideloaded.

**Summary generation is fully on-device** and produces:

- **Headline** — one line: hours captured, words spoken, dominant language
- **Timeline** — hour-by-hour activity, with the busiest windows called out
- **Topics** — TF-IDF keywords scored against a rolling 7-day background corpus, so
  *today's* distinctive terms surface rather than generic frequent words
- **People** — capitalised-token and Devanagari-name heuristics (explicitly labelled as
  approximate in the UI; this is the weakest signal and should not be over-trusted)
- **Key moments** — TextRank over the day's sentences
- **Stats** — talk time, silence ratio, language mix, chunks processed

Optional: if the user supplies a Claude API key in Settings, the summary is additionally
refined by the API. **Off by default** — the app is offline unless explicitly told otherwise.

---

## 8. Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | core |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Android 14+ typed FGS while capturing |
| `FOREGROUND_SERVICE_DATA_SYNC` | summary-only wake-ups, when the mic type would be refused |
| `POST_NOTIFICATIONS` | Android 13+ notification |
| `SCHEDULE_EXACT_ALARM` | 11 PM summary |
| `RECEIVE_BOOT_COMPLETED` | post resume prompt after reboot |
| `INTERNET` | model download only |
| `WAKE_LOCK` | partial wakelock keeps the read loop alive with screen off |

`minSdk 29`, `targetSdk 36`.

---

## 9. Privacy posture

Audio and transcripts never leave the device. Storage is app-private
(`filesDir` / `noBackupFilesDir`), excluded from cloud backup via `allowBackup=false` and
`dataExtractionRules`. Deletion is real: the WAV is unlinked, and "Delete everything" wipes
DB + files.

This app records people who have not consented. That is a genuine legal exposure — India's
DPDP Act governs processing others' personal data, and any use in a two-party-consent
jurisdiction or under GDPR is a real problem. The UI therefore keeps recording state
unmistakable (persistent notification, always-visible status). This is a personal prototype,
not a shippable product, and the README says so.

---

## 10. Module layout

```
app/
  src/main/
    cpp/                  whisper-jni.cpp  +  CMakeLists.txt
    java/com/mandar/echo/
      audio/              RecordingService, AudioChunker, WavWriter, MicWatchdog
      stt/                WhisperEngine (JNI), ModelManager, TranscriptionScheduler
      data/               Room: entities, DAOs, EchoDatabase, repositories
      summary/            SummaryEngine, TextRank, Keywords, DailySummaryWorker, alarms
      ui/                 theme/, screens/, components/
      EchoApp.kt, MainActivity.kt
  src/test/               JVM unit tests (chunk math, WAV headers, summary engine)
third_party/whisper.cpp   shallow clone, arm64-v8a only
```
