# echo-batch

One scheduled Cloud Run job. It reaps finished transcription batches, then
submits whatever the phone has uploaded since. See
`../docs/ARCH-2026-08-10-batch-first.md` for why it looks like this.

## Run a pass

```bash
pip install -r requirements.txt
python -m echo_batch.main          # dynamic batching, ~24 h, $0.003/min
ECHO_URGENT=1 python -m echo_batch.main   # standard tier, minutes, $0.016/min
```

Idempotent by construction: a pass that crashes anywhere leaves audio either
unclaimed (picked up next time) or claimed by a recorded operation (reaped next
time). Running it twice does nothing the first run did not already do.

## The invariant that matters

Audio is deleted only when its transcript is committed to Firestore, per file.
"Committed" means the result was successfully *read* -- not that it contained
words, since a silent recording is a real answer. A file whose result cannot be
read keeps its recording and has its claim released so a later pass retries it.

This is not theoretical. The first end-to-end run of this pipeline stored zero
segments and deleted the audio anyway: under `GcsOutputConfig` the inline
transcript is empty and the real payload sits at `file_result.uri`, so reading
the inline field looked exactly like silence.

## Layout

| | |
|---|---|
| `config.py` | environment-shaped settings, one place |
| `pipeline.py` | `reap()` then `submit()`; claim state lives on the GCS object |
| `main.py` | one pass, then exit |

## Infrastructure

```
gs://agentbillboard-echo-ingest    pending/ audio, 1-day lifecycle backstop
gs://agentbillboard-echo-results   out/ BatchRecognizeResults JSON, 1-day
firestore db "echo"                echo_batches, echo_segments
```

Both buckets carry a one-day delete rule so audio cannot outlive its purpose
even if the reaper never runs.
