# Transcription, redesigned: batch-first, latency-last

**Status:** proposal, with the load-bearing claim measured. Nothing migrated yet.
**Constraint:** GCP only. Everything above that is reconsidered from scratch.

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
hallucinates over silence — and becomes an 8× cost lever. Worth retuning for that
reason alone (`AUDIT` §D flags its threshold as a fraction *of chunk length*, which
is wrong at any chunk size and very wrong at the 1 minute currently configured).

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

1. ✅ **Measure Chirp 3 on the existing fixtures.** `eval/chirp3_eval.py`. Done — §2.
2. **Re-measure on real room audio.** The fixture result is necessary, not
   sufficient. Record far-field Marathi, write the reference by hand, re-run. This
   is the decision point, not step 1.
3. Terraform the bucket + Eventarc + Firestore + the three functions.
4. Client: replace the piece machine with a resumable upload and a Firestore
   listener. This is where the ~1,500 lines come out.
5. Cut over one day of audio, both paths in parallel, diff the transcripts.
6. Retire `vexyl-stt`.

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
