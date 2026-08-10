"""Everything environment-shaped, in one place.

Deliberately plain module constants read from the environment rather than a
config framework: this service is one scheduled job with six settings, and the
indirection would cost more than it buys.
"""

from __future__ import annotations

import os

PROJECT = os.environ.get("ECHO_PROJECT", "agentbillboard")

# Chirp 3 is not in every region. `us` is a multi-region and is where the
# fixture evaluation actually answered -- see eval/chirp3_eval.py.
SPEECH_LOCATION = os.environ.get("ECHO_SPEECH_LOCATION", "us")

INGEST_BUCKET = os.environ.get("ECHO_INGEST_BUCKET", "agentbillboard-echo-ingest")
RESULTS_BUCKET = os.environ.get("ECHO_RESULTS_BUCKET", "agentbillboard-echo-results")

# Its own database, not the one another project already uses in this GCP project.
FIRESTORE_DB = os.environ.get("ECHO_FIRESTORE_DB", "echo")

PENDING_PREFIX = "pending/"

# Audio the phone has uploaded but which no batch has claimed yet is found by
# the *absence* of this metadata key. The key holds the operation that claimed
# it, which is also how the reaper finds its way back to the audio to delete.
CLAIM_KEY = "echoBatchOp"

# BatchRecognize accepts several files per request. Kept low on purpose: a
# request is the unit of failure, and an 8-hour file that fails costs 8 hours.
MAX_FILES_PER_BATCH = int(os.environ.get("ECHO_MAX_FILES_PER_BATCH", "5"))

# DYNAMIC_BATCHING is the 24-hour tier at $0.003/min, and is the entire cost
# argument for this design. Set ECHO_URGENT=1 to fall back to the standard tier
# ($0.016/min) when a result is actually needed now -- the hybrid described in
# docs/ARCH-2026-08-10-batch-first.md §6.
URGENT = os.environ.get("ECHO_URGENT", "") == "1"

# "auto" lets Chirp 3 pick. Whether that survives genuinely code-switched
# hi/mr/en is an open question in the design doc, not a settled fact.
LANGUAGE_CODES = os.environ.get("ECHO_LANGUAGES", "auto").split(",")

# Diarization is GA in 14 languages including Hindi and English -- but NOT
# Marathi. Off by default: labels present for part of a code-switched day and
# absent for the rest is worse than uniformly absent.
DIARIZE = os.environ.get("ECHO_DIARIZE", "") == "1"

BATCHES_COLLECTION = "echo_batches"
SEGMENTS_COLLECTION = "echo_segments"
