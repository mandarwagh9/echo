# Transcription, redesigned: batch-first, latency-last

**Status:** the server side is built, deployed and verified end to end. The client
is half built — protocol and upload loop exist and are tested; nothing is wired
into the recorder. Nothing is migrated, and the measurement that would justify
migrating has not been made.
**Constraint:** GCP only. Everything above that is reconsidered from scratch.

> **§9 records where building contradicted this document.** Three of the choices
> below turned out to be wrong once run. Read §9 before treating §3 as a plan.

---

## 1. The thesis

Echo's transcription path is engineered for latency it does not need.

The product surface is a journal you read in the evening and a summary written at
23:00. Nothing in it is interactive. Yet the current design pays for near-real-time
delivery with: a self-hosted 600M model on Cloud Run, a global inference lock, an
in-process job dict that loses jobs when an instance recycles, a 300 s request cap
that forces 120-second piece-splitting, a client-side resubmit budget, `LOST` and
`REJECTED` and halt states, park backoffs, and roughly 1,500 lines of durable-queue
machinery living in the phone's database.

All of that exists to deliver, within minutes, a transcript nobody reads for hours.

Chirp 3's dynamic-batch tier inverts the trade: **accept up to 24 hours of latency,
and the cost, the operations and most of the code disappear.**

## 2. The gate: is Chirp 3 actually good enough at Marathi?

The self-hosted IndicConformer exists for exactly one reason — on-device Whisper
measured **0.00** word recall on Marathi. Everything below depends on Chirp 3
covering that. Measured with `eval/chirp3_eval.py`, which reuses
`IndicSpeechInstrumentedTest`'s recall metric verbatim so the numbers compare to
the ones already in `VERIFICATION.md`:

| fixture | Chirp 3 | Whisper Base | IndicConformer |
|---|---|---|---|
| `hindi.wav` | **1.00** | 0.23 | 1.00 |
| `marathi.wav` | **1.00** | 0.00 | 1.00 |
| `codeswitch.wav` | 0.62 | 0.08 | 0.67 |
| `jfk.wav` | 0.91 | 1.00 | n/a |

Hindi and Marathi returned **word for word identical to the reference**. The two
scores below 1.00 are artefacts of the metric, not errors:

- `codeswitch` — Chirp 3 transliterates the English into Devanagari (`sensor` →
  `सेंसर`, `pending` → `पेंडिंग`). The sentence is right. This is the *same*
  artefact `VERIFICATION.md` §5.3 already records against IndicConformer's 0.67.
- `jfk` — `And` vs `and`. The metric is case-sensitive; the transcript is perfect,
  and better punctuated than Whisper's.

**What this does not establish.** The fixtures are 16 kHz clips synthesised by
Google Cloud Text-to-Speech. Scoring Google's ASR against Google's own synthesis
flatters it, and says little about far-field Marathi in a noisy room, which is the
real workload. The honest reading is *"not obviously worse than IndicConformer"*,
not *"as good"*. Before any migration completes, this has to be re-run on real
recorded room audio with a hand-written reference.

Conditional on that: **the self-hosted server has no remaining reason to exist.**

## 3. Shape

```
PHONE                                   GCP
─────                                   ───
mic ─► VAD gate ─► Opus ─┐
                         │  resumable upload, signed URL
                         └────────────►  gs://echo-ingest/{day}/{chunk}.ogg
                                              │  Eventarc: object finalized
                                              ▼
                                         ingest fn  ──►  Firestore: chunk QUEUED
                                              │
                                              ▼  Cloud Scheduler, hourly
                                         batcher job
                                           BatchRecognize(
                                             model = chirp_3,
                                             processing_strategy = DYNAMIC_BATCHING,
                                             language_codes = [auto | hi-IN | mr-IN],
                                             diarization,
                                             GcsOutputConfig)
                                              │
                                              ▼  Eventarc on the results object
                                         reaper fn
                                           parse BatchRecognizeResults
                                           ─► Firestore transcripts (ts, speaker, text)
                                           ─► delete the source audio
                                              │
                       Firestore listener ◄───┘
                                              │
                                              ▼  Cloud Scheduler, 23:00
                                         summariser ─► Gemini Flash ─► summary doc
```

**The GCS object is the queue.** That single substitution is where the complexity
goes. A resumable upload either completes or resumes; the object exists or it does
not. There is no piece table, no fingerprint, no offset arithmetic, no resubmit
budget, no `LOST`, because there is no bespoke protocol to lose anything in.

## 4. What this deletes

| Today | Under this design |
|---|---|
| `cloud_jobs` piece state machine (`NEW→SUBMITTED→COMPLETED/REJECTED/LOST`) | gone — one object, one file in one batch |
| 120 s piece splitting, `offsetMs`, `streamFingerprint` | gone — `BatchRecognize` takes files up to **8 hours** |
| Client resubmit budget, `MAX_CONSECUTIVE_RESUBMITS` | gone — GCS durability replaces it |
| `CloudGate` halt/park/backoff | shrinks to "is there network to upload" |
| Cold starts, global inference lock, one job at a time | gone — managed, no instance to warm |
| Self-hosted IndicConformer + its Dockerfile, HF token, deploy | gone |
| Cloud Run instance-hours 24/7 | zero idle; billed per second of audio |
| Language sent per piece, frozen at plan time | `language_codes=["auto"]` |
| No speaker labels | diarization, GA in Chirp 3 (**Hindi yes, Marathi no**) |

The lease/claim design in `TranscriptionPipeline` stays — it is genuinely good, and
something still has to decide when a local WAV may be deleted. It just has far less
to coordinate.

## 5. Cost

Chirp 3 standard is **$0.016/min** ($0.96/h); dynamic batch is **$0.003/min**
($0.18/h). Gemini Flash audio bills 25 tokens/second — 90,000 tokens/hour, about
$0.09/h input.

At 720 h/month of wall clock, with the existing VAD gate passing roughly 3 h/day of
actual speech (~90 h/month):

| Path | Monthly |
|---|---|
| Everything, no gate, dynamic batch | ~$130 |
| **Voiced only, dynamic batch** | **~$16** |
| Voiced only, standard tier | ~$86 |
| Voiced only, Gemini Flash audio | ~$8 + output |

The gate stops being a correctness device — it was there because Whisper
hallucinates over silence — and becomes an 8× cost lever.

Retuned on 2026-08-11, and both `AUDIT` §D defects are closed: the bar is now an
absolute 1 s of voiced audio rather than a fraction of chunk length, and a chunk
with no real noise floor is measured from its median instead of its 20th
percentile. The second change is the one that moves this table — continuous noise
previously passed the gate end to end, so a night of television or traffic was
uploaded in full. **The 3 h/day figure above predates the fix and has not been
re-measured**; it is now an upper bound rather than an estimate. What the change is
worth in practice cannot be settled from `docs/stt-health-*` either, because the
85 %-empty night there may equally be the silenced-microphone defect (§D, P0),
which deletes audio without the gate being involved at all.

## 6. What this costs you — stated plainly

1. **Audio comes to rest in the cloud.** Today the WAV is deleted on the phone once
   its transcript commits, and nothing is stored server-side. Here, audio sits in a
   GCS bucket until the reaper deletes it. That reverses the app's central promise
   and is the one trade in this document that deserves a real argument. Mitigations —
   CMEK, uniform bucket-level access, a one-day lifecycle rule as a backstop to the
   reaper, and the same explicit opt-in the cloud backend already has — reduce the
   window but do not change the fact.
2. **Up to 24 hours of latency.** Fine for the 23:00 summary, wrong for "what did I
   say this morning" asked at 15:00. Resolve with a hybrid: the current day's chunks
   go standard tier on demand when the app is opened; everything older rides the
   batch. Standard for 1 h/day and batch for the rest is roughly $45/month.
3. **Diarization does not cover Marathi.** It is GA in 14 languages including Hindi
   and English, not Marathi. Speaker labels would be present for some of a
   code-switched day and absent for the rest, which is worse than uniformly absent
   unless the UI is explicit about it.

## 7. Build order

1. ✅ **Measure Chirp 3 on the existing fixtures.** `eval/chirp3_eval.py` — §2.
2. ⛔ **Re-measure on real room audio.** *Blocked: needs a recording.* The fixture
   result is necessary and not sufficient — see §2. **This is the decision point,
   and nothing past step 4 should happen before it.**
3. ✅ **Build the server side.** `gcp/` — one scheduled Cloud Run job, the
   signed-URL minting service, the nightly Gemini write-up. Deployed and verified
   end to end: uploaded audio is transcribed, written to Firestore under the right
   local day, and its recording deleted only after the transcript commits.
4. ✅ **Client, upload half.** `GcsUploadProtocol` + `GcsUploader`, additive and
   tested against a fake transport. Nothing in the recorder changed.
5. ⛔ **Client, pipeline half.** *Blocked: needs a decision — see §10.*
6. Run both paths over the same day and diff the transcripts.
7. Retire `vexyl-stt`. Only then do the ~1,500 lines come out.

## 8. Open questions

- Does `language_codes=["auto"]` hold up on genuinely code-switched hi/mr/en, or
  does it lock to a dominant language per file? Chirp 3's docs do not say. Test
  before relying on it — the current design already learned this the hard way when
  the server echoed back the posted code and the client stored it as a detection.
- Is 8 h/file the right granularity, or does an hour keep failure blast radius
  sensible? An 8-hour file that fails costs 8 hours.
- Does the on-device engine stay at all? It is the only thing that works with no
  network. Keeping it means keeping whisper.cpp, the NDK build and the model
  download for a path that measured 0.00 on Marathi.

## Sources

- [Chirp 3 transcription](https://docs.cloud.google.com/speech-to-text/docs/models/chirp-3) — `hi-IN`/`mr-IN` GA; diarization in 14 languages; sync/batch/streaming
- [BatchRecognize](https://docs.cloud.google.com/speech-to-text/docs/batch-recognize) — GCS URI input, 8 h/file, `DYNAMIC_BATCHING`, inline or GCS output
- [Speech-to-Text pricing](https://cloud.google.com/speech-to-text/pricing)
- [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing) — audio at 25 tokens/second

---

## 9. Where building contradicted this document

Kept rather than edited away, because the corrections are the useful part.

**Eventarc and three functions were the wrong shape.** §3 sketches an ingest
function, a batcher and a reaper wired together with Eventarc. What shipped is
*one* idempotent Cloud Run job that reaps then submits, on a schedule. Eventarc
was not even enabled on the project, but that is the weaker reason. The stronger
one: every step here is already asynchronous — resumable upload, a long-running
operation, a 24-hour latency budget — so event plumbing buys latency nothing in
the product can spend, at the cost of another API to enable, another identity to
grant and another delivery semantic to reason about. Reaping *before* submitting
in a single pass also means a run never submits a file it is about to delete.

**Dynamic batching is not slow.** The tier is priced against a 24-hour SLA, and
the production configuration returned in about **three minutes** on short files.
That makes the cost argument stronger than §5 claims. It does not make the
latency guarantee better, and nothing here may depend on the fast case.

**A signed-URL service was missing from the design entirely.** §3 draws an arrow
straight from the phone to the bucket, which cannot exist: the phone has no GCP
credentials, and a service-account key inside a sideloaded APK is a key you have
published. `echo-upload` is the smallest thing that closes that gap — it
authorises, names the object, and returns a URL. Bytes never pass through it, so
unlike the server it replaces it is not in the data path and holds no model.

**Segments needed a wall-clock anchor and did not have one.** The first working
version stored file-relative offsets, so a whole day stacked at 00:00. The chunk
start now rides in the object name — `pending/<epochMillis>.<ext>` — because that
is the one piece of metadata surviving every retry, resume and reupload for free.
Flat, not `pending/<day>/<epoch>`: carrying the day twice let the two disagree.

**Two data-loss bugs, both found by running it, neither by compiling it.** Under
`GcsOutputConfig` the inline transcript is empty and the payload sits at
`file_result.uri`; reading the inline field found nothing, which is
indistinguishable from silence — and the audio was deleted anyway, because
deletion was not conditioned on having stored something. And there is no
`resultEndOffset` in the payload, so every segment came back `0..0`. Deletion is
now per file and gated on the result having been *read*, which is the same
invariant `TranscriptionPipeline` already enforces on the phone.

## 10. The open decision, and why it is not mine to make

Batch transcription breaks the assumption the chunk state machine rests on.
Today a chunk is claimed, transcribed and settled in one pass, and `audioHold`
answers a single question: *has a transcript good enough to justify deleting this
recording been stored?*

Under batch there is no such moment. The transcript arrives hours later, out of
band, in Firestore. So `audioHold` has to answer a different question, and the
plausible answer is that a confirmed upload means the durable copy has **moved**
to GCS — making the local WAV redundant at upload time rather than at transcript
time.

That is probably right. It is also exactly the reasoning that produced both
data-loss bugs in §9, and several more in `AUDIT-2026-08-06`: every one of them
was a defensible-sounding argument for deleting audio slightly earlier than the
evidence justified. The cost of being wrong is the only copy of someone's day.

So it wants a deliberate decision, not an inference — and it should be made after
step 2, not before, because if Chirp 3 does not hold up on real room audio then
the question is moot and the existing path stays.

---

## 11. Every consumer of `status` and `audioHold`, checked against `UPLOADED`

Adding one state to a state machine with a dozen implicit consumers produced six
bugs, found one per pass over several days, each in the seam between the new code
and an old invariant rather than in the new code itself. This is the sweep that
should have happened first. It is recorded so the next person adding a state has
the list rather than the archaeology.

`UPLOADED` is **not terminal** (work remains, elsewhere) and **not claimable**
(`nextClaimableId` takes only `PENDING`). It holds `AudioHold.AWAITING_REMOTE`.

| Consumer | Behaviour with `UPLOADED` | |
|---|---|---|
| `nextClaimableId` — `PENDING` only | not claimed by the worker | correct |
| `requeueStale` — `TRANSCRIBING` only | untouched | correct |
| `recoverAbandonedRecording` — `RECORDING` | untouched | correct |
| `pendingCount` — `PENDING`/`TRANSCRIBING` | excluded — invisible on Today | **fixed**: separate counter |
| `failedChunks`, `retryAllFailed`, `discardFailedWithoutAudio` — `FAILED` | out of reach of "Retry failed" | **fixed**: `reclaimStuckUploads` |
| `requeueRedoable` — `DONE`/`SILENT` + holds | out of reach of "Redo" | same fix |
| `tradeableAudio` — `DONE`/`SILENT` + `DEGRADED` | its audio can never be traded | **fixed**: cap checked before upload |
| `retainedAudioBytes` — status-agnostic, any non-`UNLINK_PENDING` hold | **counts it** — pressure reads true | correct as written |
| `releasableLeftovers` — `UNLINK_PENDING` | never carries that marker | correct |
| `chunksWithAudio` — `filePath IS NOT NULL` | audio safe from the orphan sweep | correct |
| `unsettledBetween` — `RECORDING`/`PENDING`/`TRANSCRIBING` | day looked settled when it was not | **fixed**: `UPLOADED` added |
| `outstandingChunkId` — `SUBMITTED` job on a `PENDING`/`TRANSCRIBING` chunk | cannot block other chunks | correct |
| `settle` → `clearForChunk` when `isTerminal` | stale `cloud_jobs` survive the hand-off | **fixed**: cleared explicitly |
| Model gate — `modelFile == null && !cloudSelected` | pipeline dead with no local model | **fixed**: "transcribes elsewhere" |
| `SummaryEngine` buckets `DONE`/`SILENT`/`FAILED` | counted in the total, in no bucket | **fixed**: provisional wired |

Two of those came back clean, and that is worth as much as the fixes:
`retainedAudioBytes` is status-agnostic, so disk pressure reads correctly without
help, and `chunksWithAudio` keys on `filePath` rather than status, so an
uploaded chunk's recording is never mistaken for an orphan and deleted.

**The generalisation.** Every one of these bugs came from a query that named the
states it *wanted* rather than the property it *meant*. `!cloudSelected` meant
"has no remote transcriber". `status IN ('DONE','SILENT')` meant "settled".
Enumerating states is correct until someone adds one, and this state machine now
has eight. Where a predicate can be phrased as the property, it should be.
