"""Mint a resumable-upload URL for one chunk. That is the whole service.

The phone cannot hold GCP credentials -- a service-account key shipped inside a
sideloaded APK is a key you have published -- so something has to stand between
the recorder and the bucket. This is deliberately the smallest thing that can:
it authorises, it names the object, and it hands back a URL. Bytes never pass
through it, so it is not in the data path and cannot become a bottleneck, a
cost centre or a place transcripts leak from.

Contrast with what it replaces. The old server received the audio, held it in
memory, ran a 600M model behind a global lock, kept job state in a process
dictionary and had to stay warm to answer. This returns a string.

Signing is keyless, via the IAM signBlob API, so there is no private key in the
image, in Secret Manager, or on disk anywhere.

The object name is `pending/<epochMillis>.<ext>` because the epoch is the only
wall-clock anchor a transcript ever gets -- see echo_batch/pipeline.py.
"""

from __future__ import annotations

import datetime
import json
import os

import google.auth
import google.auth.transport.requests
from flask import Flask, jsonify, request
from google.cloud import firestore, storage

app = Flask(__name__)

PROJECT = os.environ.get("ECHO_PROJECT", "agentbillboard")
BUCKET = os.environ.get("ECHO_INGEST_BUCKET", "agentbillboard-echo-ingest")
API_KEY = os.environ.get("ECHO_UPLOAD_KEY", "")
FIRESTORE_DB = os.environ.get("ECHO_FIRESTORE_DB", "echo")
URL_TTL = datetime.timedelta(minutes=int(os.environ.get("ECHO_URL_TTL_MIN", "60")))

# A 10-minute chunk of 16 kHz mono PCM is 19.2 MB. The ceiling is generous
# enough for an hour of Opus and small enough that a bug cannot fill a bucket.
MAX_BYTES = int(os.environ.get("ECHO_MAX_BYTES", str(256 * 1024 * 1024)))

ALLOWED_EXT = {"wav", "ogg", "opus", "flac"}

_credentials, _ = google.auth.default()
_storage = storage.Client(project=PROJECT)


def _signing_identity() -> tuple[str, str]:
    """Who signs, and the token that authorises the signing call.

    Both are required for keyless signing. `generate_signed_url` only takes the
    IAM signBlob path when it is handed an email *and* a live access token; give
    it either alone and it falls back to looking for a private key, and a
    metadata-server credential has none -- which fails as "you need a private
    key to sign credentials", pointing at the one solution this avoids.

    The token is the part that expires, so refresh on validity rather than on
    the email being unset.
    """
    if not _credentials.valid:
        _credentials.refresh(google.auth.transport.requests.Request())
    return _credentials.service_account_email, _credentials.token


@app.get("/health")
def health():
    # Unauthenticated on purpose and deliberately trivial: it reveals nothing
    # and does no work. The service it replaces had an unauthenticated /health
    # that woke a GPU-sized instance, which meant anyone with the URL could bill
    # the project around the clock (AUDIT-2026-08-06 §F).
    return jsonify(ok=True)


@app.post("/v1/upload-url")
def upload_url():
    if not API_KEY or request.headers.get("X-Echo-Key") != API_KEY:
        return jsonify(error="unauthorized"), 401

    body = request.get_json(silent=True) or {}

    started = body.get("startedAtMs")
    if not isinstance(started, int) or started <= 0:
        return jsonify(error="startedAtMs must be a positive epoch-millisecond integer"), 400

    ext = str(body.get("ext", "ogg")).lower().lstrip(".")
    if ext not in ALLOWED_EXT:
        return jsonify(error=f"ext must be one of {sorted(ALLOWED_EXT)}"), 400

    size = body.get("sizeBytes")
    if isinstance(size, int) and size > MAX_BYTES:
        return jsonify(error=f"sizeBytes exceeds {MAX_BYTES}"), 413

    content_type = str(body.get("contentType", "application/octet-stream"))
    name = f"pending/{started}.{ext}"

    email, token = _signing_identity()
    # Resumable, because this uploads over a phone radio: a dropped connection
    # has to be able to continue rather than start a 19 MB chunk again.
    url = _storage.bucket(BUCKET).blob(name).generate_signed_url(
        version="v4",
        expiration=URL_TTL,
        method="RESUMABLE",
        content_type=content_type,
        service_account_email=email,
        access_token=token,
    )
    return jsonify(url=url, object=name, bucket=BUCKET, expiresInSeconds=int(URL_TTL.total_seconds()))


@app.post("/v1/segments")
def segments():
    """Return the transcripts for named objects, if they exist yet.

    Exact match on object name rather than a time window, and a POST rather than
    a GET, for the same reason: the phone knows precisely which chunks it is
    waiting for, and asking for those is not a query that can drift, need an
    index, or quietly miss a late arrival because it fell outside a window.

    A missing object is simply absent from the reply. That is the normal case --
    dynamic batching means most requests arrive before the transcript does --
    and it must be cheap and unremarkable rather than an error.
    """
    if not API_KEY or request.headers.get("X-Echo-Key") != API_KEY:
        return jsonify(error="unauthorized"), 401

    body = request.get_json(silent=True) or {}
    names = body.get("objects")
    if not isinstance(names, list) or not names:
        return jsonify(error="objects must be a non-empty array of object names"), 400
    if len(names) > 200:
        return jsonify(error="at most 200 objects per request"), 400

    uris = [f"gs://{BUCKET}/{str(n).lstrip('/')}" for n in names]

    db = firestore.Client(project=PROJECT, database=FIRESTORE_DB)
    found: dict[str, list[dict]] = {}
    # Firestore caps an `in` filter at 30 values.
    for i in range(0, len(uris), 30):
        window = uris[i:i + 30]
        query = db.collection("echo_segments").where(
            filter=firestore.FieldFilter("sourceUri", "in", window)
        )
        for doc in query.stream():
            row = doc.to_dict()
            uri = row.get("sourceUri", "")
            found.setdefault(uri.rsplit("/", 1)[-1] if uri else "", []).append({
                "text": row.get("text", ""),
                "language": row.get("language", ""),
                "startMs": row.get("startMs", 0),
                "endMs": row.get("endMs", 0),
                "startedAt": row.get("startedAt"),
            })

    for segs in found.values():
        segs.sort(key=lambda s: s["startMs"])
    return jsonify(segments=found)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8080)))
