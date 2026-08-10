"""Entry point. One pass, then exit.

A Cloud Run *job* rather than a service: there is nothing to serve. Cloud
Scheduler runs it, it does the work, it exits, and nothing is billed in between.
The previous architecture's standing Cloud Run instance -- holding a 600M model
warm so it could answer within seconds -- is the cost this replaces.

Local:   python -m echo_batch.main
"""

from __future__ import annotations

import logging
import sys

from .pipeline import run_once
from .summarise import summarise, today


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [echo-batch] %(levelname)s %(message)s",
    )
    # Devanagari in a transcript must survive the log. The server this replaces
    # set no encoding and reached Cloud Logging as literal "?" -- 0 of 2,422
    # completions contained a single Devanagari codepoint, which made transcript
    # quality unauditable. See docs/stt-health-2026-08-07-to-09.md §4.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    # One image, two schedules. `--summarise` is the 23:00 write-up; with no
    # argument this is the every-15-minutes transcription pass.
    if "--summarise" in sys.argv:
        day = next((a for a in sys.argv[1:] if a.startswith("20")), today())
        summarise(day)
        return 0

    result = run_once()
    logging.getLogger("echo.batch").info(
        "pass complete: submitted %d file(s) in %d batch(es); "
        "reaped %d batch(es) -> %d segment(s); %d still running",
        result.submitted_files,
        result.submitted_batches,
        result.reaped_batches,
        result.written_segments,
        result.still_running,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
