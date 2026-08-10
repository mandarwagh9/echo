"""Write up a day, from the segments the batch pipeline stored.

Replaces the on-device summariser (TextRank + a capitalisation heuristic for
names). Two reasons that heuristic had to go rather than be tuned: it finds
names by looking for capital letters, so it works in English and misses Hindi
and Marathi names entirely -- in an app whose speech is mostly Devanagari -- and
TextRank extracts sentences that already exist rather than saying what happened.

The thing this must not repeat is subtler than either. The on-device summariser
buckets DONE, SILENT and FAILED chunks but counts *every* chunk in the total, so
a day whose audio had not finished transcribing was written up as "no speech
detected, 0 words transcribed", with no failure line, and stored as final. A
summary that is confidently wrong about a day is worse than no summary, so
completeness is computed first and an incomplete day is marked provisional and
told to the model, which is asked to say so.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime

from google import genai
from google.cloud import firestore, storage
from google.genai import types

from . import config
from .pipeline import TZ, _chunk_start_ms, _day_of, _db

log = logging.getLogger("echo.summarise")

MODEL = "gemini-2.5-flash"

SCHEMA = {
    "type": "object",
    "properties": {
        "headline": {"type": "string"},
        "narrative": {"type": "string"},
        "topics": {"type": "array", "items": {"type": "string"}},
        "people": {"type": "array", "items": {"type": "string"}},
        "openLoops": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["headline", "narrative", "topics", "people", "openLoops"],
}

PROMPT = """You are writing a private journal entry for the person who was \
wearing the recorder. These are transcribed fragments of their day, in the \
order they were spoken.

Write in the language the day was mostly lived in. If the speech is mostly \
Hindi or Marathi, write in that language; if it is mixed, write in whichever \
dominates and keep the other's words where they were used. Do not translate \
names.

- headline: one line, what this day was.
- narrative: 3-6 sentences. What actually happened, in order. Be concrete and \
specific. Do not pad, and do not invent anything that is not in the fragments.
- topics: the few distinctive subjects of this day, not generic ones.
- people: names of people mentioned. Devanagari names count -- do not skip them \
because they are not capitalised. Empty list if none are named.
- openLoops: things left unresolved, promised, or scheduled. Empty if none.

{completeness}

Fragments:
{fragments}
"""


def _fragments(db: firestore.Client, day: str) -> list[dict]:
    docs = db.collection(config.SEGMENTS_COLLECTION).where(
        filter=firestore.FieldFilter("day", "==", day)
    ).stream()
    rows = [d.to_dict() for d in docs]
    rows.sort(key=lambda r: r.get("startedAt") or 0)
    return rows


def _completeness(db: firestore.Client, day: str) -> tuple[bool, str]:
    """Is this day finished, and if not, why not.

    Two ways a day can still be arriving: a batch is running, or audio is
    sitting in the bucket that nothing has claimed yet.
    """
    running = sum(
        1 for _ in db.collection(config.BATCHES_COLLECTION)
        .where(filter=firestore.FieldFilter("status", "==", "RUNNING"))
        .stream()
    )
    # Counted by the day derived from each object's epoch name, not by a path
    # prefix: the epoch is the only place the day is authoritative, and the
    # pending bucket is small by construction because audio is deleted as soon
    # as its transcript commits.
    gcs = storage.Client(project=config.PROJECT)
    unclaimed = 0
    for blob in gcs.bucket(config.INGEST_BUCKET).list_blobs(
        prefix=config.PENDING_PREFIX
    ):
        if blob.name.endswith("/"):
            continue
        started = _chunk_start_ms(blob.name)
        if started is not None and _day_of(started) == day:
            unclaimed += 1
    if running or unclaimed:
        return False, (
            f"{running} batch(es) still transcribing and {unclaimed} recording(s) "
            "not yet submitted"
        )
    return True, ""


def summarise(day: str) -> dict | None:
    db = _db()
    fragments = _fragments(db, day)
    if not fragments:
        log.info("%s: nothing transcribed, no summary written", day)
        return None

    complete, why = _completeness(db, day)

    lines = []
    for row in fragments:
        started = row.get("startedAt")
        clock = (
            datetime.fromtimestamp(started / 1000, TZ).strftime("%H:%M")
            if started else "--:--"
        )
        lines.append(f"[{clock}] ({row.get('language', '?')}) {row['text']}")

    note = (
        "This day is COMPLETE."
        if complete
        else (
            f"This day is INCOMPLETE: {why}. Say so in one short sentence at the "
            "end of the narrative. Do not describe the day as quiet or empty on "
            "the strength of what is missing."
        )
    )

    # google-genai, not vertexai.generative_models: the latter was deprecated in
    # June 2025 with a stated removal date of June 2026, which has passed. It
    # still answers today, which is exactly the kind of thing to migrate off
    # before it stops rather than after.
    client = genai.Client(
        vertexai=True, project=config.PROJECT, location=config.VERTEX_LOCATION
    )
    response = client.models.generate_content(
        model=MODEL,
        contents=PROMPT.format(completeness=note, fragments="\n".join(lines)),
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=SCHEMA,
            temperature=0.3,
        ),
    )
    summary = json.loads(response.text)

    doc = {
        **summary,
        "day": day,
        "provisional": not complete,
        "incompleteReason": why or None,
        "segments": len(fragments),
        "model": MODEL,
        "generatedAt": firestore.SERVER_TIMESTAMP,
    }
    # Keyed by day, so re-running replaces rather than accumulates -- a
    # provisional summary is meant to be overwritten once the day completes.
    db.collection("echo_summaries").document(day).set(doc)
    log.info(
        "%s: summarised %d fragment(s)%s",
        day, len(fragments), "" if complete else " (provisional)",
    )
    return doc


def today() -> str:
    return datetime.now(TZ).strftime("%Y-%m-%d")
