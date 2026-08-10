"""One scheduled pass: reap what finished, then submit what arrived.

The ordering is not arbitrary. Reaping first frees the audio a completed batch
was holding before the same pass goes looking for new work, so a run never
submits a file it is about to delete.

There is no ingest function and no Eventarc subscription here, and that is the
point. Every step in this pipeline is already asynchronous -- the phone's upload
is resumable, BatchRecognize is a long-running operation, and the whole design
accepts up to 24 hours of latency. Event plumbing buys latency that nothing in
the product can spend, at the cost of another API to enable, another identity to
grant and another delivery semantic to reason about. A single idempotent job on
a schedule does the same work and can be run by hand.

Claim state lives on the GCS object as custom metadata rather than in a table.
An object is either claimed by an operation or it is not, and that fact travels
with the bytes -- which is what made the previous design's separate piece table
able to disagree with reality.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import datetime
from zoneinfo import ZoneInfo

from google.api_core.client_options import ClientOptions
from google.cloud import firestore, storage
from google.cloud.speech_v2 import SpeechClient
from google.cloud.speech_v2.types import cloud_speech

from . import config

TZ = ZoneInfo(config.TIMEZONE)

log = logging.getLogger("echo.batch")


@dataclass(frozen=True)
class PassResult:
    submitted_files: int
    submitted_batches: int
    reaped_batches: int
    written_segments: int
    still_running: int


def _speech() -> SpeechClient:
    return SpeechClient(
        client_options=ClientOptions(
            api_endpoint=f"{config.SPEECH_LOCATION}-speech.googleapis.com"
        )
    )


def _db() -> firestore.Client:
    return firestore.Client(project=config.PROJECT, database=config.FIRESTORE_DB)


# --------------------------------------------------------------------------
# submit
# --------------------------------------------------------------------------

def _unclaimed(bucket: storage.Bucket) -> list[storage.Blob]:
    """Audio the phone has uploaded that no operation owns yet.

    Oldest first: a day's transcript is more useful filled in from the start,
    and it keeps a backlog draining in the order it accumulated.
    """
    blobs = [
        b for b in bucket.list_blobs(prefix=config.PENDING_PREFIX)
        if not b.name.endswith("/")
        and not (b.metadata or {}).get(config.CLAIM_KEY)
    ]
    blobs.sort(key=lambda b: b.time_created)
    return blobs


def _recognition_config() -> cloud_speech.RecognitionConfig:
    features = cloud_speech.RecognitionFeatures(
        enable_automatic_punctuation=True,
        enable_word_time_offsets=True,
    )
    if config.DIARIZE:
        features.diarization_config = cloud_speech.SpeakerDiarizationConfig(
            min_speaker_count=1,
            max_speaker_count=6,
        )
    return cloud_speech.RecognitionConfig(
        auto_decoding_config=cloud_speech.AutoDetectDecodingConfig(),
        language_codes=list(config.LANGUAGE_CODES),
        model="chirp_3",
        features=features,
    )


def submit(speech: SpeechClient, bucket: storage.Bucket, db: firestore.Client) -> tuple[int, int]:
    pending = _unclaimed(bucket)
    if not pending:
        return 0, 0

    strategy = (
        cloud_speech.BatchRecognizeRequest.ProcessingStrategy.PROCESSING_STRATEGY_UNSPECIFIED
        if config.URGENT
        else cloud_speech.BatchRecognizeRequest.ProcessingStrategy.DYNAMIC_BATCHING
    )

    files = batches = 0
    for i in range(0, len(pending), config.MAX_FILES_PER_BATCH):
        group = pending[i:i + config.MAX_FILES_PER_BATCH]
        uris = [f"gs://{bucket.name}/{b.name}" for b in group]

        request = cloud_speech.BatchRecognizeRequest(
            recognizer=(
                f"projects/{config.PROJECT}/locations/"
                f"{config.SPEECH_LOCATION}/recognizers/_"
            ),
            config=_recognition_config(),
            files=[cloud_speech.BatchRecognizeFileMetadata(uri=u) for u in uris],
            recognition_output_config=cloud_speech.RecognitionOutputConfig(
                gcs_output_config=cloud_speech.GcsOutputConfig(
                    uri=f"gs://{config.RESULTS_BUCKET}/out"
                ),
            ),
            processing_strategy=strategy,
        )
        operation = speech.batch_recognize(request=request)
        op_name = operation.operation.name

        # Firestore first, then the claim. If this process dies between the two,
        # the batch is recorded and the audio simply gets picked up again -- a
        # duplicate transcript, which is recoverable. The other order loses the
        # operation entirely and strands the audio claimed by nothing.
        db.collection(config.BATCHES_COLLECTION).document(_doc_id(op_name)).set({
            "operation": op_name,
            "uris": uris,
            "status": "RUNNING",
            "urgent": config.URGENT,
            "submittedAt": firestore.SERVER_TIMESTAMP,
        })
        for blob in group:
            blob.metadata = {**(blob.metadata or {}), config.CLAIM_KEY: op_name}
            blob.patch()

        log.info("submitted %d file(s) as %s", len(group), op_name)
        files += len(group)
        batches += 1

    return files, batches


# --------------------------------------------------------------------------
# reap
# --------------------------------------------------------------------------

def _doc_id(operation_name: str) -> str:
    """Firestore ids cannot contain '/', and operation names are paths."""
    return operation_name.replace("/", "_")


def _chunk_start_ms(uri: str) -> int | None:
    """The chunk's wall-clock start, carried in the object name.

    The recorder uploads to `pending/<epochMillis>.ogg`, because a transcript
    with only file-relative offsets cannot be placed on a timeline or grouped
    into a day -- and the object name is the one piece of metadata that survives
    every retry, resume and reupload for free.

    Flat, with no date directory. An earlier form was
    `pending/<YYYY-MM-DD>/<epochMillis>.ogg`, which carries the day twice and
    lets the two disagree: the day is derived from the epoch, but the
    completeness check counted unsubmitted recordings by path prefix, so a
    mismatch made a day look finished while its audio sat in another folder.
    One source of truth removes the failure instead of guarding it.

    None for anything not following the convention (the eval fixtures), which
    callers treat as "no wall-clock anchor" rather than "epoch zero".
    """
    stem = uri.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    return int(stem) if stem.isdigit() else None


def _day_of(epoch_ms: int) -> str:
    """The local day a moment belongs to, as YYYY-MM-DD.

    Local, not UTC: a journal's "day" is the one the person lived, and in IST a
    UTC day boundary falls at 05:30 -- which would split every morning in two.
    """
    return datetime.fromtimestamp(epoch_ms / 1000, TZ).strftime("%Y-%m-%d")


def _offset_ms(value: str | None) -> int:
    """Parse the JSON duration form ("0.280s", "3s") into milliseconds."""
    if not value:
        return 0
    return int(round(float(str(value).rstrip("s")) * 1000))


def _segments_from(gcs: storage.Client, result: cloud_speech.BatchRecognizeFileResult) -> list[dict]:
    """Read one file's transcript.

    Under GcsOutputConfig the inline `transcript` is empty and `uri` points at a
    BatchRecognizeResults JSON in the results bucket. Reading the inline field
    and finding nothing looks exactly like silence, which is how the first run
    of this pipeline stored zero segments and then deleted the audio anyway.

    Raises rather than returning [] if the result cannot be read, because the
    caller uses "did this parse" to decide whether the recording is expendable.
    """
    if not result.uri:
        raise RuntimeError("file result carried neither transcript nor uri")

    prefix = "gs://"
    bucket_name, _, path = result.uri[len(prefix):].partition("/")
    payload = gcs.bucket(bucket_name).blob(path).download_as_text()

    out = []
    for res in (json.loads(payload).get("results") or []):
        alternatives = res.get("alternatives") or []
        if not alternatives:
            continue
        alt = alternatives[0]
        text = (alt.get("transcript") or "").strip()
        if not text:
            # An empty transcript is a successful answer about silence, not a
            # failure -- there is simply nothing to store for it.
            continue
        # Timings come from the words, because the result level carries only
        # `alternatives` and `languageCode` -- there is no resultEndOffset in
        # this payload, and reading for one silently produced 0..0 for every
        # segment, which would have put a whole day at midnight. Note the first
        # word omits startOffset entirely when it begins at zero.
        words = alt.get("words") or []
        out.append({
            "text": text,
            "language": res.get("languageCode") or "",
            "startMs": _offset_ms(words[0].get("startOffset")) if words else 0,
            "endMs": _offset_ms(words[-1].get("endOffset")) if words else 0,
            "speakers": sorted({
                w["speakerLabel"] for w in words if w.get("speakerLabel")
            }),
        })
    return out


def reap(speech: SpeechClient, bucket: storage.Bucket, db: firestore.Client) -> tuple[int, int, int]:
    running = db.collection(config.BATCHES_COLLECTION).where(
        filter=firestore.FieldFilter("status", "==", "RUNNING")
    ).stream()

    reaped = written = still_running = 0
    for doc in running:
        record = doc.to_dict()
        op_name = record["operation"]
        operation = speech.transport.operations_client.get_operation(op_name)

        if not operation.done:
            still_running += 1
            continue

        if operation.HasField("error") and operation.error.code:
            # The batch is over and produced nothing. Release the claim so the
            # audio is retried rather than stranded: it is the only copy.
            log.error("%s failed: %s", op_name, operation.error.message)
            _release(bucket, record["uris"])
            doc.reference.update({
                "status": "FAILED",
                "error": operation.error.message,
                "finishedAt": firestore.SERVER_TIMESTAMP,
            })
            reaped += 1
            continue

        response = cloud_speech.BatchRecognizeResponse.deserialize(
            operation.response.value
        )

        gcs = storage.Client(project=config.PROJECT)
        batch_written = 0
        # Per file, because one bad file in a batch of five must not decide the
        # fate of the other four's recordings.
        settled: list[str] = []
        unsettled: list[str] = []

        for uri, file_result in response.results.items():
            if file_result.error and file_result.error.code:
                log.error("%s: %s", uri, file_result.error.message)
                unsettled.append(uri)
                continue
            try:
                segments = _segments_from(gcs, file_result)
            except Exception as exc:  # noqa: BLE001 - unreadable result, keep the audio
                log.error("%s: could not read result (%s)", uri, exc)
                unsettled.append(uri)
                continue
            chunk_start = _chunk_start_ms(uri)
            for segment in segments:
                # Absolute epoch times where the object name gave us an anchor.
                # Without this a day's segments all sit near zero and sort by
                # nothing useful.
                absolute = (
                    {
                        "startedAt": chunk_start + segment["startMs"],
                        "endedAt": chunk_start + segment["endMs"],
                        "day": _day_of(chunk_start + segment["startMs"]),
                    }
                    if chunk_start is not None
                    else {}
                )
                db.collection(config.SEGMENTS_COLLECTION).add({
                    **segment,
                    **absolute,
                    "sourceUri": uri,
                    "operation": op_name,
                    "createdAt": firestore.SERVER_TIMESTAMP,
                })
                batch_written += 1
            # Zero segments here is a *read* result that contained no speech,
            # which is a real answer about silence. The invariant is "the result
            # was read", not "the result had words".
            settled.append(uri)

        # Only the files whose transcripts are committed lose their recording.
        # Anything unreadable keeps its audio and its claim is released so a
        # later pass retries it: this is the only copy of that audio, and a
        # stored transcript is what earns the right to delete it -- the same
        # rule TranscriptionPipeline enforces on the phone.
        _delete(bucket, settled)
        _release(bucket, unsettled)

        doc.reference.update({
            "status": "DONE" if not unsettled else "PARTIAL",
            "segments": batch_written,
            "unsettled": unsettled,
            "finishedAt": firestore.SERVER_TIMESTAMP,
        })
        written += batch_written
        reaped += 1
        log.info("reaped %s -> %d segment(s)", op_name, batch_written)

    return reaped, written, still_running


def _blobs_for(bucket: storage.Bucket, uris: list[str]) -> list[storage.Blob]:
    prefix = f"gs://{bucket.name}/"
    return [bucket.blob(u[len(prefix):]) for u in uris if u.startswith(prefix)]


def _release(bucket: storage.Bucket, uris: list[str]) -> None:
    for blob in _blobs_for(bucket, uris):
        if not blob.exists():
            continue
        blob.reload()
        metadata = dict(blob.metadata or {})
        metadata.pop(config.CLAIM_KEY, None)
        blob.metadata = metadata
        blob.patch()


def _delete(bucket: storage.Bucket, uris: list[str]) -> None:
    for blob in _blobs_for(bucket, uris):
        if blob.exists():
            blob.delete()


def run_once() -> PassResult:
    speech, bucket, db = _speech(), storage.Client(project=config.PROJECT).bucket(
        config.INGEST_BUCKET
    ), _db()

    reaped, written, still_running = reap(speech, bucket, db)
    files, batches = submit(speech, bucket, db)

    return PassResult(
        submitted_files=files,
        submitted_batches=batches,
        reaped_batches=reaped,
        written_segments=written,
        still_running=still_running,
    )
