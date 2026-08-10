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

## Deployed

`./deploy.sh` stands the whole thing up and is idempotent. Live now in
`agentbillboard`:

| | |
|---|---|
| Cloud Run job | `echo-batch`, us-central1, 512 Mi, 900 s timeout |
| Schedule | `echo-batch-tick`, `7,22,37,52 * * * *` Asia/Kolkata |
| Identity | `echo-batch@` — `speech.client`, `datastore.user`, and `storage.objectAdmin` **per bucket**, not project-wide |

Verified end to end through the deployed job: audio uploaded to
`pending/` was submitted on one tick, reaped on the next, written to Firestore
with correct Devanagari and real word-derived timings, and the source audio
deleted only after the transcript committed.

**Measured latency:** the production `DYNAMIC_BATCHING` tier returned in about
three minutes on short files. The 24 hours in the pricing tier is a worst-case
SLA, not the expected wait — but the design must not depend on the fast case,
because nothing guarantees it.

**Transcripts are never logged.** The server this replaces wrote full transcript
text to Cloud Logging on every job (`AUDIT-2026-08-06` §F, P1/privacy). The
Dockerfile still sets `LANG`/`PYTHONIOENCODING` so any Devanagari that does reach
a log line — an error message, say — survives as text rather than `?`.

## echo-upload

The phone cannot hold GCP credentials — a service-account key inside a
sideloaded APK is a key you have published — so one small service stands between
the recorder and the bucket. It authorises, names the object, and returns a
resumable-upload URL. **Bytes never pass through it**, so it is not in the data
path and cannot become a bottleneck, a cost centre, or a place transcripts leak
from. The server it replaces received the audio, held it in memory, ran a 600M
model behind a global lock and had to stay warm to answer. This returns a string.

```
POST /v1/upload-url        X-Echo-Key: <key>
{"startedAtMs": 1786358637983, "ext": "wav", "contentType": "audio/wav"}
-> {"url": "<v4 signed resumable URL>", "object": "pending/1786358637983.wav"}
```

Then the client does the standard two-step: `POST` with `x-goog-resumable: start`
to open a session, `PUT` the bytes to the returned session URI. Resumable because
this uploads over a phone radio, where a dropped connection must continue rather
than restart a 19 MB chunk.

Signing is keyless via IAM `signBlob` — no private key in the image, in Secret
Manager, or on disk. Note that `generate_signed_url` only takes that path when
given **both** a service-account email and a live access token; with either
alone it looks for a private key and fails with the misleading "you need a
private key to sign credentials".

`/health` is unauthenticated and deliberately does nothing. Its predecessor's
unauthenticated health check woke a model-sized instance, so anyone with the URL
could bill the project around the clock (`AUDIT-2026-08-06` §F).

**Verified end to end:** minted a URL, opened a session (201), PUT a real clip
(200), and the scheduled job picked it up, transcribed it, wrote it to Firestore
under the right local day, and deleted the audio.
