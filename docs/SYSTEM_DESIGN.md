# Echo — System Design

A 24/7 ambient audio journal for Android. Records continuously, transcribes in 10-minute
chunks, deletes the audio once deletion is *proven* safe, and produces a summary of your day
at 11:00 PM.

Transcription runs on-device by default (whisper.cpp). A self-hosted server backend
(IndicConformer-600M) is available as an explicit opt-in, because on-device Whisper measured
0.23 word recall on Hindi and 0.00 on Marathi — see [§7](#7-stt-backends).

Target: **one device** (the developer's own phone). Prototype quality, production discipline.

---

## 1. Product requirements (verbatim, restated)

| # | Requirement | Design response |
|---|---|---|
| R1 | Records 24/7 | Foreground service, `microphone` type, persistent notification |
| R2 | 10-minute chunks | Sample-exact rotation at 9,600,000 samples (600 s × 16 kHz) |
| R3 | Transcribe each chunk | whisper.cpp via JNI, arm64-v8a; optionally a self-hosted IndicConformer server |
| R4 | Delete audio after transcription | WAV unlinked only once the transcript is committed **and** proven complete — see [§5.6](#56-when-a-wav-may-be-deleted) |
| R5 | **Every minute is recorded** | Mic read decoupled from disk + STT by a bounded handoff queue |
| R6 | Transcription in background | Dedicated coroutine, never on the recorder thread |
| R7 | Offline, on-device STT | whisper.cpp, no network at inference time. The cloud backend is off by default and is a deliberate, visible trade |
| R8 | Multilingual EN / HI / MR | Whisper multilingual models; IndicConformer for HI/MR when enabled |
| R9 | Daily summary at 11 PM | Exact alarm → summary worker → reschedule |
| R10 | Black & white UI | Monochrome Compose design system |

---

## 2. Pipeline

```
                    ┌──────────────────────────────────────────┐
                    │  RecordingService (foreground, mic type) │
                    └──────────────────────────────────────────┘
                                       │
   ┌───────────────────────────────────┼───────────────────────────────────┐
   │                                   │                                   │
┌──▼─────────────────┐   handoff  ┌────▼──────────────┐          ┌─────────▼──────────┐
│  Recorder thread   │  queue     │  Writer thread    │          │ TranscriptionPipe- │
│  URGENT_AUDIO prio │ ─────────▶ │  WAV I/O          │          │ line (one coroutine│
│                    │  (bounded, │  + chunk rotation │          │  one chunk at a    │
│ AudioRecord.read() │   64 buf)  │  + header patch   │          │  time)             │
│ 16 kHz mono PCM16  │            └────┬──────────────┘          └─────────┬──────────┘
│ NEVER blocks       │                 │                                   │
└────────────────────┘                 │ chunk closed                      │
                                       ▼                                   │
                              ┌──────────────────┐   claim (lease)         │
                              │  Room: chunks    │ ◀───────────────────────┘
                              │  status=PENDING  │                         │
                              └──────────────────┘                         ▼
                                                         ┌────────────────────────────┐
                                                         │ VAD voiced gate            │
                                                         │   ↓                        │
                                                         │ cloud pieces  ┆  Whisper    │
                                                         │   ↓                        │
                                                         │ segments + status  (1 txn,  │
                                                         │   conditional on the lease) │
                                                         │   ↓                        │
                                                         │ audioHold?  no → delete WAV │
                                                         │             yes → keep it   │
                                                         └────────────────────────────┘
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
- **Transcription** is a separate coroutine entirely. whisper is CPU-heavy (4 threads);
  running it anywhere near the recorder thread would cause dropouts. The cloud transport gets
  its own `Dispatchers.IO.limitedParallelism(2)` for the same reason.

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
steady-state disk use is 2–3 chunks ≈ 60 MB. That 19.2 MB is the unit of the retained-audio
arithmetic in [§5.7](#57-the-1-gb-retained-audio-cap).

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

Schema version **2**. `MIGRATION_1_2` (`data/EchoDatabase.kt`) is additive only; there
is deliberately no `fallbackToDestructiveMigration` on the builder, so a schema mismatch is a
crash rather than a silent wipe of the user's day.

```kotlin
@Entity chunks
  id                Long PK autoincrement
  startedAt         Long        // epoch ms of first sample
  endedAt           Long?       // epoch ms of last sample
  filePath          String?     // null once audio deleted
  sampleCount       Long
  status            RECORDING | PENDING | TRANSCRIBING | DONE | SILENT | FAILED | DISCARDED
  attempts          Int         // real failures only; 3 ⇒ FAILED
  audioDeleted      Boolean
  error             String?
  transcribeMs      Long?       // wall-clock cost, drives the realtime-factor gauge
  speechRatio       Float
  wordCount         Int

  // --- added in schema 2, the durable-queue columns ---
  claimedAt         Long?       // the LEASE: when it was taken, and the lease token itself
  notBefore         Long        // park deadline; PENDING is claimable only when notBefore <= now
  claims            Int         // total claims taken. Diagnostic.
  abandonedClaims   Int         // claims that ended with no outcome written (process died)
  transientFailures Int         // parks. Never decides FAILED.
  transcriptSource  String?     // "cloud" | "device" | null
  voicedMs          Long        // voiced ms the VAD gate found — the denominator
  coveredMs         Long        // voiced ms a stored transcript actually covers — the numerator
  audioHold         String?     // why this WAV must survive its own transcript, or null

@Entity cloud_jobs                       // one server-sized piece of one chunk's upload
  chunkId     Long   ─┐ composite PK
  pieceIndex  Int    ─┘
  state       NEW | SUBMITTED | COMPLETED | REJECTED | LOST
  jobId       String?           // the server's job id once accepted
  offsetMs    Long              // position in the COMPACTED voiced stream
  durationMs  Long
  languageSent String            // frozen at first submit ("hi-IN" | "mr-IN")
  streamFingerprint String       // of the exact post-gain, post-gate array uploaded
  transcript  String?
  submittedAt Long?
  resubmits   Int               // consecutive re-uploads after the server lost the job;
                                //   cleared by an answered POLL, never by a submit
  error       String?
  FK chunkId -> chunks(id) ON DELETE CASCADE   [indexed]

@Entity segments                 // one row per backend segment
  id       Long PK
  chunkId  Long FK -> chunks(id) ON DELETE CASCADE   [indexed]
  startMs  Long                  // absolute epoch ms
  endMs    Long
  text     String
  language String                // "en" | "hi" | "mr"

@Fts4 segments_fts(text)         // full-text search over the day

@Entity summaries
  dayEpochDay  Long PK           // LocalDate.toEpochDay()
  generatedAt  Long
  headline     String
  bodyMarkdown String
  statsJson    String
  provisional  Boolean           // schema-only — see §5.9
```

---

## 5. Transcription queue

This is the part that was rebuilt in `6639bc0`, from a fire-and-forget loop into a durable
state machine. The live symptom was 18 chunks queued and 0 words written.

**What this section is, and what it is not.** The state machines and the invariants are the
durable part: they say what has to be true, and by what mechanism. The *status* column in
[§5.9](#59-invariants-and-whether-they-hold) is not durable — it is a reading of the committed
tree at `6639bc0`, taken 2026-08-06, and at that moment a follow-up addressing several of the
not-enforced entries was sitting uncommitted and incomplete in the working tree. So read §5.9
as **the checklist that any follow-up has to satisfy**, not as a claim about a particular
build. Nothing is marked enforced that was not observed enforced, and nothing that the code
already does is described as future work. Symbols are named rather than cited by line, because
the files are moving.

### 5.1 The chunk state machine

`TRANSCRIBING` is not really a state — it is a **lease**. The real state of a chunk is the
triple `(status, claimedAt, notBefore)`:

- `claimedAt` is both *when* the lease was taken and the lease **token**. Every terminal write
  carries the token it was issued and is applied only if the row still holds it.
- `notBefore` is the park deadline.
- A chunk is *claimable* iff `status = 'PENDING' AND notBefore <= now`
  (`ChunkDao.nextClaimableId`).

```
      AudioChunker.openChunk                     recoverAbandonedRecording
      inserts the row                            (startedAt < now - (chunkMinutes + 5 min))
              │                                            │
              ▼                                            ├── WAV missing ──▶ DISCARDED
      ┌───────────────┐                                    │
      │   RECORDING   │────────────────────────────────────┘
      │ filePath set  │      WAV present: repairIfTruncated, infer sampleCount
      │ sampleCount 0 │
      └───────┬───────┘
              │  closeChunk(endedAt, sampleCount)      ┌──────────────────────────────┐
              │  the only write that sets either       │  requeueStale(cutoff)        │
              ▼                                        │  abandonedClaims += 1        │
      ┌───────────────────────────┐                    │  claimedAt = NULL            │
      │          PENDING          │◀───────────────────┤  (startup: cutoff = now;     │
      │  claimable iff            │                    │   sweep: now - 45 min)       │
      │  notBefore <= now         │                    └──────────────▲───────────────┘
      └─────────────┬─────────────┘                                   │
         ▲          │  claimNext(now)  — one @Transaction:            │ process died
         │          │    nextClaimableId → markClaimed → byId         │ with no write
         │          │  status=TRANSCRIBING, claimedAt=now, claims+1   │
         │          ▼                                                 │
         │  ┌──────────────────────────────────────────────┐          │
    park │  │              TRANSCRIBING                    ├──────────┘
  defer- │  │  a LEASE, not a state: claimedAt is the      │
   Chunk └──┤  token every terminal write must present     │
            └───┬──────────┬──────────┬──────────┬─────────┘
                │          │          │          │
                │          │          │          └── commitSilent ────▶ SILENT
                │          │          │
                │          │          └───────────── commitText ──────▶ DONE
                │          │
                │          └── failChunk, attempts >= 3 ─────────────▶ FAILED
                │
                └───── failChunk, attempts < 3 ──▶ PENDING (notBefore = 0)
```

Every edge, with the exact write and what it does to `attempts`:

| Edge | Trigger | DAO write | `attempts` |
|---|---|---|---|
| → RECORDING | `AudioChunker.openChunk` → `RecordingService.onChunkStarted` | `insert` | — |
| RECORDING → PENDING | sample-exact rotation, `closeChunk` | `closeChunk(id, endedAt, sampleCount, PENDING)` | untouched |
| RECORDING → PENDING | crash recovery, WAV present | `repairIfTruncated` then `closeChunk` | untouched |
| RECORDING → DISCARDED | crash recovery, WAV gone | `setStatus(DISCARDED)` | untouched |
| PENDING → TRANSCRIBING | `claimNext(now)` | `markClaimed`, `claims + 1` | untouched |
| TRANSCRIBING → PENDING | **park** (`Attempt.Park`) | `deferChunk(notBefore = now + wait, transientFailures + 1)` | **untouched — deliberately** |
| TRANSCRIBING → DONE | text committed | `commitTranscript` (segments + `finishChunk`, one txn, lease-conditional) | `attempts` written |
| TRANSCRIBING → SILENT | no speech, or nothing survives the gate | same, `segments = []`, `transcriptSource = null` | `attempts` written |
| TRANSCRIBING → PENDING | real failure, `attempts < 3` | `finishChunk(PENDING)`, also sets `notBefore = 0` | `attempts` written |
| TRANSCRIBING → FAILED | real failure, `attempts >= 3` | `finishChunk(FAILED, audioHold = "failed; kept so it can be retried")` | `attempts` written |
| TRANSCRIBING → FAILED | `filePath` null, or file gone | `finishChunk(FAILED, error = "audio file missing")` | `attempts + 1` |
| TRANSCRIBING → PENDING | lease recovery | `requeueStale(cutoff)`, `abandonedClaims + 1` | **untouched** |
| FAILED → PENDING | user taps "Retry N failed" | `retryAllFailed()` — zeroes every counter | reset to 0 |
| TRANSCRIBING → PENDING | one-time, on upgrade | `MIGRATION_1_2` | untouched |

Two of these have no caller in the app and are listed for completeness:
`requeueDegraded()` (the redo path — see [§5.8](#58-the-redo-path-audiohold--transcriptsource))
and `discardFailedWithoutAudio()`.

Claiming is deliberately ordered so that **a chunk with work already running on the server is
claimed first** (`ChunkDao.nextClaimableId`):

```sql
ORDER BY (SELECT COUNT(*) FROM cloud_jobs
          WHERE cloud_jobs.chunkId = chunks.id AND cloud_jobs.state = 'SUBMITTED') DESC,
         startedAt ASC
```

Collect what the server has already been paid to produce before starting a new upload;
otherwise fall back to oldest-first.

### 5.2 The piece state machine (`cloud_jobs`)

A chunk is 600 s. The server caps a request at 300 s and each queued job pins its decoded
float32 PCM in memory, so Echo uploads in 120-second **pieces**, cut from the *compacted
voiced stream* (what survives the VAD gate), not from wall-clock time.

Piece state lives in the database, not on a coroutine's stack. That is the whole point:
parking a chunk must not forfeit work the server is already running, and resuming must not
re-upload megabytes the server still holds.

```
  planPieces: do all the stored rows carry the current streamFingerprint?
      yes ──▶ reuse them exactly as they stand        (the resume path)
      no  ──▶ clearForChunk, then rebuild at MAX_PIECE_SECONDS = 120,
              freezing offsetMs / durationMs / languageSent now
                     │
                     ▼
              ┌─────────────┐  2xx with job_id    ┌─────────────┐
              │     NEW     │───────────────────▶ │  SUBMITTED  │
              └──────┬──────┘                     └──────┬──────┘
                 ▲   │                                   │
                 │   │ 400 / 413                    poll │
                 │   ▼                                   │
                 │  ┌─────────────┐ ◀── status = failed ─┤
                 │  │  REJECTED   │ ◀── unknown status ──┤
                 │  └─────────────┘                      │  status = completed AND
                 │                                       │  the "transcript" key is
                 │  404 JobGone, budget left             │  PRESENT — content
                 └───────────────────────────────────────┤  is irrelevant
                                    │                    │
                                    │ 404 JobGone,       ▼
                                    │ budget spent  ┌─────────────┐
                                    ▼               │  COMPLETED  │
                             ┌─────────────┐        └─────────────┘
                             │    LOST     │
                             └─────────────┘
```

Terminal states are `COMPLETED`, `REJECTED` and `LOST`. Everything else routes to a **park**:
403 and 3xx/404-on-submit halt the whole cloud path; 429, 5xx, transport failure, an
unparseable 2xx, and "server still working past the ceiling" all park.

The `JobGone` edge is `resubmitAfterLoss`, and it always demotes the row off `SUBMITTED`,
because a job the server cannot find is a job that will never produce a result. What it does
*not* always do is retry. The counter is `resubmits`, and two properties make it a real bound
rather than a documented one:

- **Cleared by an answered poll, never by a submit.** An accepted upload proves only that
  some instance took the bytes; it says nothing about whether that instance will still exist
  at the first poll. Clearing on submit meant every run re-read its own submit and saw `0`, so
  `MAX_CONSECUTIVE_RESUBMITS` was unreachable and a lost job was re-uploaded — 3.8 MB, on a
  metered radio — on every park, forever.
- **Exhaustion is terminal, not another `NEW`.** Writing `state = NEW` when the budget is gone
  *is* an instruction to upload again, so the bound has to be spent at the one site that
  decides: a row left `NEW` always has budget, and a piece out of budget is `LOST`. One piece
  is therefore uploaded at most `1 + MAX_CONSECUTIVE_RESUBMITS = 3` times per plan.

`LOST` is deliberately not `REJECTED`, because the two want opposite answers about the WAV. A
refusal is about the audio and is final. A loss is about the server — an instance recycling
between the upload and the first poll, or, since session affinity is cookie-based and
`HttpURLConnection` sends no cookies, a service scaled past one instance where a poll can
*never* find its own job. Both give the caller no text, so both spans are covered on device;
only `LOST` sets `PieceResult.retryable`, which makes the chunk's transcript **provisional**,
which holds the audio as `DEGRADED` and lists the chunk for redo. Collapsing the two would
delete the only copy of audio IndicConformer would have transcribed perfectly an hour later.

Two serialisation rules, because the server runs one inference at a time behind a global lock
and its 429 threshold (1000 pending jobs) would OOM the instance long before it fired:

- **Cross-chunk:** before every piece, `jobs.outstandingChunkId()` is checked. If any *other*
  chunk holds a `SUBMITTED` row, this chunk parks with "another chunk is holding the server's
  only worker".
- **Within a chunk:** `advance` returns `Done` only when the row reached a terminal state
  (`CloudJobState.isTerminal`), so the loop never reaches piece *n+1* while piece *n* is still
  `SUBMITTED`.

**`streamFingerprint`** is computed over the exact post-gain, post-gate float array that gets
uploaded. Any change to the gate or the gain therefore invalidates stored offsets and forces a
rebuild, rather than silently relabelling audio that has moved underneath its own timestamps.

**`languageSent`** is frozen at plan time and read back when the result is turned into
segments. It is never re-read from settings, so changing the language mid-flight cannot
mislabel a transcript the server produced under the old one.

**Empty transcript is a success.** The readiness test is *key presence*, not `isNotBlank()`.
The server answers a silent job with `200 {"status":"completed","transcript":""}`, which is an
ordinary success; the old client could not represent "finished and produced no words" and
waited for a state that had already happened. That is one of the three defects this rewrite
exists to make unrepresentable.

### 5.3 Why parking exists

A chunk that is waiting on something external — a cold server, no network, another chunk
holding the server's only worker — **parks**: back to `PENDING` with a `notBefore`, worker
released, `cloud_jobs` rows left intact. It does not hold the head of the queue, and it does
not fall back to the on-device engine.

The old behaviour was to fall back. That is wrong, and the measurement says how wrong. Word
recall against known references, same fixtures, same day:

| | Hindi | Marathi | code-switched | English |
|---|---|---|---|---|
| Whisper Base (on-device default) | 0.23 | **0.00** | 0.08 | 1.00 |
| Whisper Small (on-device) | 0.54 | 0.13 | 0.38 | 1.00 |
| **IndicConformer-600M (server)** | **1.00** | **1.00** | 0.67\* | — |

\* The code-switched score is a scoring artefact: IndicConformer transliterates "sensor" and
"pending" into Devanagari while the reference keeps them in Latin. The sentence is right.

Marathi is the case that settles it. 0.00 is not "worse", it is *nothing* — a day of Marathi
run through Whisper produces a transcript that is not a transcript. Falling back to it because
a lift had no signal for ninety seconds destroys the day's record and then deletes the audio
that could have fixed it.

So:

- **A park must never burn an attempt.** `deferChunk`'s SQL does not mention `attempts` at
  all. Before this, three network blips permanently downgraded a chunk from 1.00 to 0.00 by
  exhausting its retries against a server that was merely asleep.
- **A park is not a failure and is not counted as one.** It increments `transientFailures`,
  which never participates in the `attempts >= 3 ⇒ FAILED` decision.
- **Park length is chosen by what is being waited on**, clamped to
  `[PARK_SHORT_MS 12 s, PARK_LONG_MS 10 min]`:

  | Waiting on | Park |
  |---|---|
  | a job this chunk already has outstanding | 12 s — short so the SUBMITTED-first claim order can actually fire |
  | the cloud made progress this round | 12 s |
  | a configuration halt (403 / 404 / 3xx) | 10 min |
  | nothing more specific | 60 s |

`processNext` returns **false** on a park, and the loop sleeps for `nextClaimableAt - now`
clamped to 2–15 s. That is a contract change: under the old code a claim always ended in an
outcome, so `processNext() == true` after a claim was a safe assertion. It no longer is — see
[§5.10](#510-what-the-rewrite-invalidated-elsewhere).

One asymmetry worth knowing, because it is not obvious: a **real** failure gets no backoff at
all. `finishChunk` sets `notBefore = 0`, so a non-terminal failure is immediately
re-claimable. Only parks back off.

### 5.4 The lease

`TRANSCRIBING` used to be a state a chunk could be stranded in. A hang that spared the process
was never recovered, and "TRANSCRIBING for more than N minutes" was a query nobody could
write, because nothing recorded *when*.

Now `claimedAt` records it, and doubles as the token:

- `claimNext(now)` is one `@Transaction` around `nextClaimableId` → `markClaimed` → `byId`, so
  the claim and the fetch cannot address different rows.
- Every terminal write goes through
  `finishChunk(... ) WHERE id = :id AND claimedAt = :lease`.
- `commitTranscript` wraps `deleteForChunk` + `insertAll` + `finishChunk` in one transaction
  and throws `LeaseLostException` if `finishChunk` updated 0 rows — which rolls the segment
  writes back too. A run stretched past the watchdog by Doze therefore rolls back rather than
  double-committing the day.
- `requeueStale(cutoff)` recovers leases older than `LEASE_TIMEOUT_MS` (45 min), at most every
  5 minutes. At startup the cutoff is `now`, because nothing from a previous process can still
  be holding one.

45 minutes is generous on purpose: recovering a live claim early costs the work twice,
recovering a dead one late costs only latency.

### 5.5 Backend selection, per chunk

```
usesCloud(cfg)  ⇔  sttBackend == CLOUD
                ∧  url starts with "http"
                ∧  CloudTranscriber.supports(language)      // "hi", "mr", "auto" only
```

English is deliberately absent from `SUPPORTED`. The server's batch path never validates
`language_code` and coerces anything outside its map to Malayalam — and `en-IN` is not in the
map. That failure *looks* like success: HTTP 200, non-empty text, stored and labelled English.
The only safe fix from a client that cannot edit the server is never to send it. Whisper is
fine at English anyway; it is the Indic languages it fails.

With the cloud selected, the "no speech model installed" check is skipped, so a device with no
downloaded model no longer piles up chunks forever.

### 5.6 When a WAV may be deleted

**Storing a transcript is not on its own proof that the audio is expendable.** Two ways a
stored transcript can be worth less than the audio it came from:

1. **A hole.** A multi-piece cloud upload where one piece was rejected settles with
   `coveredMs < voicedMs`. The word count is non-zero, the transcript looks fine, and part of
   the chunk was never transcribed by anything. There was previously nowhere to record that.
2. **A provisional transcript.** A device transcript taken while the server was configured but
   unavailable is 0.23/0.00-grade text standing in for 1.00-grade text. It is re-transcribable
   only while the WAV exists.

Both set `audioHold`, and the rule is:

```
hold = when {
    underPressure                            -> null    // see §5.7 — deliberate override
    coveredMs + 1000 ms < voicedMs           -> "part of this chunk was never transcribed"
    source == DEVICE && usesCloud(cfg)       -> "transcribed on device while the server
                                                 was unavailable"
    else                                     -> null
}

delete the WAV  ⇔  hold == null  ∧  !keepAudioAfterTranscription
                   ∧  the lease-conditional commit returned without throwing
```

The 1000 ms tolerance absorbs per-piece integer division across a chunk.

**Where audio is actually unlinked.** The class comment on `ChunkStatus` says audio is unlinked
"from exactly one place, under one guard — see `TranscriptionPipeline.releaseAudioIfProven`".
No such function exists. Three paths delete a WAV:

| Path | Runs | Deletes |
|---|---|---|
| `releaseAudio` — from `commitText` / `commitSilent` | right after a lease-conditional commit | the chunk it just committed, if `hold == null` and the user has not asked to keep audio |
| `releaseLeftoverAudio` — over `releasableLeftovers()` | every pipeline start | terminal chunks whose file is somehow still on disk |
| `reconcileOrphanAudio` | every pipeline start | `.wav` files that no row points at |

The second is the hard one, and it is where this design is easiest to get wrong. **A startup
sweep can only see rows.** It has no settings, no backend, and no memory of what the run that
wrote the row decided. So its predicate has to match a decision that was *recorded*, not
re-derive one from the shape of the row — and the absence of a hold is not a recorded decision.

As read at `6639bc0` the predicate is
`status IN ('DONE','SILENT') AND filePath IS NOT NULL AND audioHold IS NULL`, and
`audioHold IS NULL` cannot tell three different situations apart:

- cleared for release, then the process died before the unlink — the one case the sweep exists
  for;
- **the user turned on "keep audio after transcription"** — a cleanly transcribed chunk is left
  in character-for-character that state, so the sweep deletes it on the next service start
  (boot, recording toggled off and on again, any restart). The setting holds until then and no
  longer;
- a row written before the column existed at all.

The sweep is not optional: a crash between "transcript committed" and "file unlinked" would
otherwise leak 19 MB permanently, because nothing else ever revisits a terminal chunk. So the
fix is not a settings check bolted onto the sweep. It is to make the single-guard invariant
`Entities.kt` already claims actually true — have the committing run record a positive marker
meaning *cleared for release, unlink not done yet*, inside the same transaction as the terminal
status, and have the sweep match that marker. Then the sweep only ever finishes a job it can
see was started, and any row it cannot account for is inert rather than fair game.

`reconcileOrphanAudio` has a separate, pre-existing hazard: `AudioChunker.openChunk` creates
the WAV *before* `onChunkStarted` inserts the row, and `beginCapture()` runs before
`pipeline.start`, so there is a window in which the sweep can see the file being written right
now with no row pointing at it. Narrower than it was — the rewrite added three DB operations
ahead of the sweep — but the ordering, not the timing, is the fix.

### 5.7 The 1 GB retained-audio cap

Parking rather than falling back means a long outage releases **no** audio at all. The
arithmetic:

```
one 10-minute chunk   = 600 s × 16 000 Hz × 2 B  = 19.2 MB
MAX_RETAINED_BYTES    = 1 000 000 000 B
                      ÷ 19.2 MB                  ≈ 52 chunks
52 × 10 min           = 8 h 40 min of held audio
```

Roughly a waking day, and comfortably inside the recorder's own 1 GB low-storage pause. Past
the cap, `underPressure` is true and the trade is inverted deliberately: `holdReason` returns
null (audio is released), and the four `if (!underPressure)` guards let the cloud be bypassed
for Whisper. A degraded transcript beats no recording — but only past the point where
continuing to hold would stop the recorder, and the decision is logged rather than arriving as
a silent stop.

Two things about this that are **not** as designed:

- `retainedAudioBytes()` sums **every** row with a non-null `filePath`, including `RECORDING`
  and `PENDING`. It is total WAV on disk, not held-back WAV. It also counts rows whose
  `filePath` column is set but whose file is gone.
- Nothing releases held bytes (see §5.8). So `underPressure` is a **one-way latch**: once
  accumulated holds cross 1 GB it is permanently true, every future chunk skips the cloud for
  the 0.00-Marathi engine, and the park-don't-fall-back policy — the reason this rewrite
  exists — switches itself off for the life of the install, with no user-visible cause and no
  recovery short of "Delete everything".

### 5.8 The redo path: `audioHold` + `transcriptSource`

The design is: a held chunk keeps its WAV, the hold records *why*, and a query finds it later
and puts it back in the queue. Two of those three exist.

- A recorded reason to keep the WAV keeps it: `releaseAudio` skips the chunk, and the leftover
  sweep does not claim it. **Enforced.**
- `transcriptSource` is persisted as `"cloud"` or `"device"`, so a degraded chunk stays
  findable. **Enforced.**
- **Nothing calls the redo query.** `requeueDegraded()` and `degradedCount()` have no caller
  anywhere in the repo. A held chunk is `DONE`, therefore invisible in `pendingCount` and in
  the failed list; never redone, because nothing asks; and never released, because the sweep
  skips it. It is a permanent 19 MB with no consumer.

**The predicate has to key on the reason, not on a proxy for it.** As read at `6639bc0`,
`requeueDegraded` selects
`DONE/SILENT AND filePath NOT NULL AND audioHold NOT NULL AND transcriptSource = 'device'`.
That conjunction stands in for "the weaker engine wrote this while the better one was
configured", and it gets the population wrong in both directions:

- It **cannot see a hole.** A cloud transcript missing a rejected piece has
  `transcriptSource = 'cloud'`, so the commonest hold — the one `holdReason`'s first arm
  produces — is invisible to the query written to find it.
- It **matches things it does not mean.** Any other reason to keep an on-device chunk's audio
  satisfies the same conjunction, and requeueing one of those sends it back to be
  re-transcribed by the identical engine that already transcribed it, forever.
- Its `'SILENT'` arm is unreachable regardless: `commitSilent` always writes
  `transcriptSource = null`.

The structural fix is to store the reason as a value and match on that value, so the hold
column answers exactly one question and the redo query asks that question directly. The other
candidate key is the coverage deficit the schema already records: `coveredMs` is written and
appears in **no `WHERE` clause anywhere** in `app/src/main/java/`, though `coveredMs < voicedMs`
is precisely the evidence a hole leaves behind. Either way it needs a caller.

One part of this is not fixable client-side. A server-reported piece failure is treated as
permanent, but the server writes it from a blanket `except Exception` with a fixed string and
is structurally incapable of telling the client whether it hit an OOM or a malformed input. The
client can only choose which way to be wrong; holding the audio and keeping the chunk
re-transcribable is the cheaper wrong.

### 5.9 Invariants, and whether they hold

Marked as the code stands, not as intended.

**Claiming and the lease**

| | Invariant | Status |
|---|---|---|
| I1 | Exactly one chunk in flight at a time | **Enforced** structurally: one pipeline singleton, one coroutine behind `if (job?.isActive == true) return`, one claim per iteration. Nothing enforces that only one pipeline may exist over one database — the guard is per-instance. |
| I2 | Claim and fetch address the same row | **Enforced** — `claimNext` is one `@Transaction`. |
| I3 | A terminal transcript write is conditional on still holding the lease | **Partially.** `commitTranscript` throws `LeaseLostException` on 0 rows updated and rolls the segments back. But `failChunk` and the missing-file path call `finishChunk` directly and **discard its `Int` return** — a lost lease there silently writes nothing and is never noticed. |
| I4 | A park is not lease-conditional | **NOT ENFORCED.** `deferChunk`'s predicate is `WHERE id = :id` only, so a superseded worker's park would clobber a newer claim. Unreachable today only because of I1. |
| I5 | The lease token is unique per claim | **NOT ENFORCED.** The token is the millisecond `now`. Two claims of the same chunk in the same millisecond are indistinguishable. |
| I34 | A chunk's lease outlives its own work | **NOT ENFORCED.** `LEASE_TIMEOUT_MS` is 45 min, but one cloud round can legitimately exceed it: five 120 s pieces × a 16-minute poll ceiling each, plus a 120 s wake probe and a 10-minute transport ceiling. Harmless today only because I1 makes the sweep and the claim sequential statements in the same coroutine. |

**Attempts and failure**

| | Invariant | Status |
|---|---|---|
| I6 | A park never increments `attempts` | **Enforced** — `deferChunk`'s SQL does not mention it. This is the invariant the whole rewrite turns on. |
| I7 | A lease recovery never increments `attempts` | **Enforced** — `requeueStale` bumps `abandonedClaims` only. |
| I8 | Only a real failure drives a chunk to FAILED, after 3 of them | **Enforced.** `transientFailures` never participates. |
| I21 | A cold or unreachable server never burns an attempt and never downgrades the chunk | **Enforced** — every transient wire outcome routes to `Park`. |
| I31 | A FAILED chunk whose audio is really gone is retired rather than retried forever | **NOT ENFORCED.** The missing-file path fires when `filePath` is non-null and the file is absent, and does not null the column. `retryAllFailed` matches `filePath IS NOT NULL`, so the "Retry N failed" button requeues it, it fails again, `attempts` resets each time, and the counter never reaches zero. `discardFailedWithoutAudio` matches `filePath IS NULL` and has **no caller**, despite a comment naming it as the retirement mechanism. |
| I32 | `transientFailures` bounds something | **NOT ENFORCED.** Written by `deferChunk`, read by nothing, including `parkBackoff`. Grows without bound. |
| I33 | `abandonedClaims` bounds a crash loop | **NOT ENFORCED.** To bound anything the counter has to be (a) reset whenever a run *does* record an outcome, so it counts *consecutive* silent deaths rather than lifetime ones, and (b) read somewhere. As read at `6639bc0` it is written by `requeueStale` and read by nothing, so a JNI OOM that kills the process mid-run requeues indefinitely — exactly the case the field's own comment says it exists to catch. |

**Audio**

| | Invariant | Status |
|---|---|---|
| I9 | Audio is unlinked only when nothing is holding it and a lease-conditional commit returned | **Enforced** at both `releaseAudio` call sites, which run only after `commitTranscript` returned without throwing, and by the sweep's predicate declining to claim a held row. |
| I10 | `keepAudioAfterTranscription` is honoured | **Enforced on the commit path, NOT ENFORCED on the leftover sweep.** A startup sweep reads no settings, so the user's answer has to be encoded in the row it matches on. Matching `audioHold IS NULL` encodes nothing, and as read at `6639bc0` that is what the sweep matches. See §5.6. |
| I11 | A crash between "transcript committed" and "file unlinked" never leaks the WAV | **Enforced** by the leftover sweep at pipeline start, which handles both orders: file still present → delete, then mark; file already gone → mark. It is the only thing that ever revisits a terminal chunk, which is why I10 has to be solved *inside* it rather than by removing it. |
| I12 | A WAV no row points at is reclaimed | **Enforced** by `reconcileOrphanAudio`. Its safety against deleting the *live* recording is **NOT ENFORCED** — the file is created before the row is inserted. |
| I13 | A partial transcript never releases its audio | **Enforced** by the coverage arm of `holdReason`. |
| I14 | A device transcript taken while the cloud is configured is provisional and keeps its audio | **Enforced** for the keeping. The **consumer is not enforced**: nothing calls `requeueDegraded`, so nothing ever redeems it. See §5.8. |
| I15 | Both holds are overridden when retained audio exceeds the cap | **Enforced**, with the caveats in §5.7 about what `retainedAudioBytes` actually counts and the one-way latch. |

**The cloud path**

| | Invariant | Status |
|---|---|---|
| I16 | At most one SUBMITTED job exists server-wide | **Enforced** cross-chunk by `outstandingChunkId()` and within a chunk structurally. One escape: a re-read that finds no row returns `Done` and leaves an accepted server job untracked. |
| I17 | No SUBMITTED row survives its chunk reaching a terminal state | **NOT ENFORCED**, and this one's blast radius is global rather than per-chunk. `clearForChunk` is called from `commitSilent`, `commitText`, the missing-file path and the cloud-`Rejected` branch — but **not** from `failChunk` and not from the catch-all, so a chunk that parks holding a live SUBMITTED row and then fails (cloud park → `underPressure` → device fallback → `Attempt.Fail` → `FAILED`) leaves the row behind; `LeaseLostException` does the same. `outstandingChunkId()` then asks only "is any row SUBMITTED", with no join on chunk status, so that one orphan parks **every other chunk's** cloud path every twelve seconds for the life of the install, and the SUBMITTED-first claim order cannot rescue it because a FAILED chunk is not PENDING. Two independent mechanisms would each close it: settle the `cloud_jobs` rows on *every* terminal edge, and make the gate query name a chunk somebody will actually come back for. |
| I18 | Stored piece offsets always describe the audio in hand | **Enforced** by the all-or-nothing `streamFingerprint` check in `planPieces`. |
| I19 | A stored piece's language label is frozen at first submit | **Enforced** — `languageSent` is written once and never re-read from settings. |
| I20 | Configuration errors halt the whole cloud path and never fail a chunk | **Enforced** (403 / 3xx / 404 → `Halt` → `gate.halt`), with one caveat: under `underPressure` a halt still falls through to Whisper, so "audio untouched" holds only below the cap. |
| I22 | "The server is working" never escalates backoff | **Enforced** — `queued`/`processing` take a no-write branch, and `sawProcessing` makes a later unreachable report `progressed = true`. |
| I23 | An empty transcript is a success, not a "not ready" | **Enforced** by key-presence testing. This is the original hang, made unrepresentable. |
| I24 | The gate's server backoff is set when the server is unreachable | **NOT ENFORCED in the case that matters.** Every `CloudOutcome.Park` is constructed with `offline = false`, and `blockedReason` already covers "no network", so the only surviving trigger is a radio dying mid-round. A server that is down while wifi is fine never sets `downUntil`, which makes `DOWN_BACKOFF_MS` effectively dead code. `CloudGate.offline()` has no callers at all. |
| I25 | Wi-fi-only uploads | **NOT ENFORCED.** `wifiOnly = false` is hardcoded at the only call site, so `isMetered()` is never consulted. |
| I26 | A request can never block forever | **Enforced** by the disconnect watchdog in `HttpUrlTransport.perform` — a progress stall or a 10-minute ceiling calls `disconnect()`, which makes the blocked call throw. The reply read is wrapped in `NonCancellable` so a cancellation cannot orphan a job the server already has. |
| I35 | `clearForChunk` failures are noticed | **NOT ENFORCED.** All four call sites are bare `runCatching` with no `onFailure`. |

**Everything else**

| | Invariant | Status |
|---|---|---|
| I27 | Transcription can never starve the recorder | **Enforced** by `Dispatchers.IO.limitedParallelism(2)` for the transport and by I1. |
| I28 | A deliberate wait is always visible | **Enforced** — `PipelineState.waiting` is written on idle and on park and cleared on claim and on success. Gap: `commitSilent` does not clear it, so a stale "next try in 3 min" can survive in the Today notice. |
| I29 | Neither the migration nor a schema mismatch can wipe data | **Enforced** — `MIGRATION_1_2` is additive with SQL defaults matching every `@ColumnInfo` default, and there is no `fallbackToDestructiveMigration`. |
| I30 | Segments never double-count a chunk | **Enforced** — `deleteForChunk` precedes `insertAll` inside `commitTranscript`'s transaction, and the whole transaction rolls back on lease loss. |

**Comments that describe code which does not exist.** Listed because an engineer checking the
map against the files will otherwise trust them:

- `ChunkStatus`'s class comment — "see `TranscriptionPipeline.releaseAudioIfProven`". No such
  function.
- `ChunkEntity.transientFailures` — "Drives backoff". Nothing reads it (I32).
- `ChunkDao.retryAllFailed` — its comment claims `filePath IS NOT NULL` selects chunks whose
  audio is still on disk. It selects rows whose *column* is set, which is not the same thing
  and is why I31 fails.
- `TranscriptionPipeline`'s missing-file branch — "FAILED with no `filePath` is retired by
  `discardFailedWithoutAudio`". That function is never called, and the branch never nulls
  `filePath`, so it could not match even if it were.
- `CloudTranscriber.completedPieces` — documented as letting a device fallback keep already
  finished cloud pieces. No callers; a fallback re-decodes everything.
- `CloudGate.offline()` — documented as the "must not spend the cloud's patience" test. No
  callers (I24).
- `SubmitOutcome.Accepted.audioSeconds` — documented as timing the first poll. It is discarded;
  the first poll is timed from the row's own `durationMs`. Harmless, since the two agree by
  construction, but the comment describes code that is not there.
- `CloudOutcome.Settled.elapsedMs` — this round's wall clock only, so the realtime factor stored
  for a chunk that parked several times describes the final round, not the chunk.
- `summaries.provisional` — documented as "says so, and is rebuilt once
  the day settles". Nothing sets it, nothing reads it, and `ChunkDao.unsettledBetween` — the
  query written for exactly that check — has no callers. `SummaryEngine` buckets only `DONE`,
  `SILENT` and `FAILED` while counting every chunk in the total, so a day recorded against a
  cold server (every chunk parked, therefore `PENDING`) reports "no speech detected" and
  "0 words transcribed" with no failure line to explain it, and notifies the user with that.

### 5.10 What the rewrite invalidated elsewhere

- **The four `processNext() == true` asserts in `androidTest` no longer hold.** The new
  contract returns true only on a terminal outcome and false on a park. Independently,
  `nextClaimableId` claims the **oldest** claimable chunk, not the test's freshly inserted one,
  so on a device with a backlog the assert can fail against a chunk the test never created.
  These tests are also destructive (see `VERIFICATION.md`) and were not run.
- **`SettingsScreen`'s backend help text** still tells the user "If the server cannot be
  reached, Echo falls back to on-device rather than losing the audio." That is exactly the
  behaviour this commit removed — it now parks and waits.
- **`SettingsScreen`'s privacy copy** and `README.md` still say recordings never leave the
  device. True on the default backend, false whenever `CLOUD` is selected — and the claim sits
  directly above the control that turns the cloud on.
- **The backlog notification** labels deliberately parked chunks as "transcribing N".

---

## 6. Failure modes and their handling

| Failure | Detection | Response |
|---|---|---|
| Mic preempted (call, assistant, other app) | `AudioRecordingCallback` reports silenced, or `read()` returns error | Close current chunk cleanly, retry acquisition with backoff (1s→30s), post "paused" notification |
| Phone reboots | Service gone | `BOOT_COMPLETED` **cannot** legally start a mic FGS on Android 14+. We post a high-priority "Tap to resume" notification instead. Honest, and the only compliant option. |
| Disk fills | Free space < 1 GB | Pause recording, notify. Auto-resume at > 1.5 GB (hysteresis) |
| Transcription falls behind | pending chunks > 12 (≈2 h) | Warn in UI + notification; keep recording (R1 wins over disk) |
| **Waiting on something external** (cold server, no network, another chunk holding the server) | Wire outcome classified as transient | **Park**: back to PENDING with a `notBefore`, worker released, `cloud_jobs` kept. Never burns an attempt, never downgrades the engine |
| Transcription fails for real | Exception, or the engine returned a failed result | `attempts++`; retry ×3 with no backoff; then `FAILED` and **keep the audio** so nothing is lost |
| Server configuration wrong (403 / 404 / redirect) | Status code | Halt the whole cloud path via `CloudGate`, park for 10 min. Cleared by a success or by a settings change |
| Worker dies mid-chunk | `claimedAt` older than 45 min, or any claim at startup | `requeueStale` → PENDING, `abandonedClaims++`, `attempts` untouched |
| A run outlives its own lease | `finishChunk` updates 0 rows | `LeaseLostException` rolls the segment writes back; nothing is committed twice |
| OEM battery killer | Service death | `START_STICKY` + restart alarm + guide user to "unrestricted battery" in onboarding |
| Model missing | No file at path | Recording continues; chunks queue as PENDING until a model is installed — **unless** the cloud backend is selected, in which case no model is needed |
| Silence (most of a real day) | Adaptive energy VAD before the backend | Chunk marked SILENT, backend skipped entirely, audio deleted. Saves battery and stops whisper hallucinating text from room tone |
| `startForeground` refused | Exception on promotion | Log and `stopSelf()` rather than crash-loop on a system-initiated restart |

**Design stance:** recording is more important than transcription; transcription is more
important than disk; **a correct transcript is more important than a fast one**. Audio is never
deleted on an unproven path — with the one documented exception in §5.6, which is a defect
rather than a decision.

---

## 7. STT backends

### 7.1 On-device: whisper.cpp

Vendored and built with NDK 27 for `arm64-v8a` only (keeps APK and build footprint small on a
disk-constrained machine).

| Model | Size (q5_1) | Speed on modern arm64 | Use |
|---|---|---|---|
| tiny | ~31 MB | fastest | fallback / low-end |
| **base** | **~57 MB** | ~5–10× realtime | **default** |
| small | ~190 MB | ~1.5–3× realtime | best quality, must stay > 1× |

Downloaded in-app from HuggingFace on first run. Inference itself is fully offline.

Threads: `min(4, availableProcessors - 2)`, floor 2 — leaves headroom for the recorder.

**Whisper is an English model that tolerates Hindi.** Measured word recall, §5.3: Base scores
0.23 on Hindi and **0.00** on Marathi; Small 0.54 and 0.13. That is the limitation that
motivated a second backend, and it is a property of the model, not of the pipeline — no amount
of gate or gain tuning closes a gap that size.

### 7.2 Server: IndicConformer-600M via `vexyl-stt`

AI4Bharat's model, built for 22 Indian languages, scores **1.00 on both** the Hindi and
Marathi fixtures, and beats Google's Chirp 2 on Marathi. 600M parameters will not run on a
phone, so this is a server — `vexyl-stt`, a batch REST wrapper deployed to Cloud Run in
`asia-south1`.

**The trade is explicit and therefore a setting, not a default: CLOUD sends audio off the
device.** See [§10](#10-privacy-posture).

Client-side contract, all of it in `stt/BatchProtocol.kt` as pure functions over
`(status, body)` with 24 table tests and no socket:

| Concern | Handling |
|---|---|
| Server caps audio at 300 s; chunks are 600 s | Upload in 120 s pieces; shift returned timestamps by each piece's `offsetMs` |
| Empty transcript on a silent job | Key presence, not `isNotBlank()` — an empty string is a success |
| Failure reason | The server writes `error_message` on the status path and `error` on submit refusals; both are read in the right place |
| `en-IN` is not in the server's language map and coerces to Malayalam | English is never sent to the server |
| One inference at a time behind a global lock | One SUBMITTED job at a time, enforced client-side |
| `/health` before the API-key check | Used as the wake probe, with a 120 s budget matching Cloud Run's own startup probe |
| No persistence — jobs live in an in-process dict, evicted ~1 h after completion | 404 on poll is `JobGone` → resubmit, at most `MAX_CONSECUTIVE_RESUBMITS` times before the piece goes `LOST` |

**Language handling:** default `auto`. On-device that means per-chunk detection to survive
EN/HI/MR code-switching. The server has no auto mode, so `auto` and `hi` both post as `hi-IN`,
which is the right single default for a code-switched day; `mr` posts as `mr-IN`. The posted
code is what the segment is labelled with — the server echoes it back, so treating its reply
as a detection result was fiction written into every stored row.

---

## 8. Daily summary at 11:00 PM

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
refined by the API. **Off by default.**

**A day with parked chunks is summarised dishonestly.** The engine buckets `DONE`, `SILENT` and
`FAILED` but counts every chunk in the total, so `PENDING`, `TRANSCRIBING` and `DISCARDED`
land in the total and in no bucket. A day recorded against a cold server — every chunk parked,
therefore `PENDING`, therefore not `FAILED` — reports hours captured with "no speech detected",
"0 words transcribed", no failure line, and the closing advice to check microphone access.
The `summaries.provisional` column and `ChunkDao.unsettledBetween` were added for exactly this
and are wired to nothing. See §5.9.

---

## 9. Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | core |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Android 14+ typed FGS while capturing |
| `FOREGROUND_SERVICE_DATA_SYNC` | summary-only wake-ups, when the mic type would be refused |
| `POST_NOTIFICATIONS` | Android 13+ notification |
| `SCHEDULE_EXACT_ALARM` | 11 PM summary |
| `RECEIVE_BOOT_COMPLETED` | post resume prompt after reboot |
| `INTERNET` | model download; and the cloud backend when enabled |
| `ACCESS_NETWORK_STATE` | `CloudGate` — do not spend a wake probe with no radio |
| `WAKE_LOCK` | partial wakelock keeps the read loop alive with screen off |

`minSdk 29`, `targetSdk 36`.

---

## 10. Privacy posture

**On the default backend, audio and transcripts never leave the device.** Storage is
app-private (`filesDir` / `noBackupFilesDir`), excluded from cloud backup via
`allowBackup=false` and `dataExtractionRules`. Deletion is real: the WAV is unlinked, and
"Delete everything" wipes DB + files.

**Selecting the CLOUD backend changes that, and it is the single most consequential setting in
the app.** Voiced audio is uploaded, in 120-second pieces, to a server the user runs. It is
off by default, it is a deliberate opt-in, and it exists because the alternative on Marathi is
0.00 word recall. The UI copy has not caught up — `SettingsScreen` and the README still assert
that nothing leaves the device, directly above the control that sends it.

This app records people who have not consented. That is a genuine legal exposure — India's
DPDP Act governs processing others' personal data, and any use in a two-party-consent
jurisdiction or under GDPR is a real problem. The UI therefore keeps recording state
unmistakable (persistent notification, always-visible status). This is a personal prototype,
not a shippable product, and the README says so.

---

## 11. Module layout

```
app/
  src/main/
    cpp/                  whisper-jni.cpp  +  CMakeLists.txt
    java/com/mandar/echo/
      audio/              RecordingService, AudioChunker, WavWriter, MicWatchdog,
                          VoiceActivityDetector
      stt/                WhisperEngine (JNI), ModelManager,
                          TranscriptionPipeline   the queue orchestrator
                          CloudTranscriber        piece state machine over cloud_jobs
                          BatchProtocol           the wire contract, pure functions
                          BatchTransport          HttpURLConnection + disconnect watchdog
                          CloudGate               network / halt state shared across chunks
      data/               Room: Entities, Daos, EchoDatabase, EchoSettings
      summary/            SummaryEngine, TextRank, Keywords, DailySummaryWorker, alarms
      ui/                 theme/, screens/, components/
      EchoApp.kt, MainActivity.kt
  src/test/               JVM unit tests (chunk math, WAV headers, summary engine,
                          BatchProtocol table tests)
third_party/whisper.cpp   shallow clone, arm64-v8a only
```
