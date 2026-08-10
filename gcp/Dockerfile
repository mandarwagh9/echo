FROM python:3.12-slim

# Devanagari has to survive the log. The server this replaces set neither, and
# every transcript reached Cloud Logging as literal "?" -- 0 of 2,422 completions
# contained a single Devanagari codepoint, which made transcript quality
# impossible to audit. See docs/stt-health-2026-08-07-to-09.md §4.
ENV LANG=C.UTF-8 \
    PYTHONIOENCODING=utf-8 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY echo_batch ./echo_batch

# A job, not a server: it does one pass and exits. Nothing is billed in between.
ENTRYPOINT ["python", "-m", "echo_batch.main"]
