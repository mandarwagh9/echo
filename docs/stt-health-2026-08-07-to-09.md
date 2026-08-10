# Echo cloud STT — 2-day health check (Fri 7 – Sun 9 Aug 2026, IST)

Source: Cloud Run logs for `vexyl-stt`, project `agentbillboard`, asia-south1.
Window: 2026-08-06T18:30Z → now. Fetched in 12-hour chunks; no chunk hit the
5000-row cap, so coverage is complete.

**The phone was not connected**, so nothing on-device was checked — capture uptime,
DB growth, battery exemption, or whether chunks are queuing locally. This is the
cloud half only, which is exactly why the finding below is a symptom and not yet a
diagnosis.

---

## 1. DIAGNOSED: battery died → reboot → Echo cannot auto-resume the mic

Confirmed on-device (Pixel 9, Android 17). The chain:

| time (IST) | event | evidence |
|---|---|---|
| 00:00–07:58 | uploads 472 chunks, 85% empty — recording an empty room all night at 4–6× the usual overnight rate | cloud logs, §2 |
| 07:57:58 | last upload ever | last Cloud Run request |
| ~07:58 | **phone powers itself off, flat battery** | `sys.boot.reason=shutdown,battery` |
| 08:12 | Cloud Run scales to zero (nothing arriving, not a crash) | clean SIGTERM |
| ~10:10 | phone boots | uptime 7:33 at 17:43; kernel log begins 10:10:10 |
| 10:20:38 | `BootReceiver` posts **"Echo is not listening — Android does not let apps restart microphone recording automatically after a reboot. Tap to resume."** | usagestats `NOTIFICATION_INTERRUPTION channelId=status`; `BootReceiver.kt:45` |
| 10:20 → now | nobody tapped it. No process, no capture. | `pidof` empty, no foreground service |

**~9.5 h total gap, of which ~7.3 h is the phone sitting booted and idle waiting for
a tap.** The notification is still posted and undismissed
(`numPostedByApp=1, numRemovedByApp=0`).

This is working as designed — Android genuinely forbids restarting mic capture from
`BOOT_COMPLETED`, and `BootReceiver.kt` says so in the notification text. The design
gap is that a 24/7 recorder's recovery path depends on a human noticing a low-priority
notification. It went unnoticed for seven hours.

### Ruled out as causes

| suspect | state | verdict |
|---|---|---|
| Battery exemption revoked | `user,com.mandar.echo,10422` present | intact |
| App standby restricted | bucket 5 = EXEMPTED | intact |
| `RECORD_AUDIO` revoked | `granted=true` | intact |
| Force-stopped by user | `stopped=false` | no |
| Crash / ANR | dropbox empty for the package | no |
| Storage-full pause | 22 GB free (91% used) | no, but worth watching |

Nothing was wrong with the install. Backend is fine and cold-starts on next request.

## 2. Overnight Sat→Sun looks anomalous, not routine

Restricted to like-for-like hours (IST 00:00–07:59, the only hours Sunday has):

| night | chunks uploaded | empty transcripts |
|---|---|---|
| Fri 07 | 123 | 51.2% |
| Sat 08 | 79 | 64.6% |
| Sun 09 | **472** | **85.0%** |

Sunday overnight uploaded ~4–6× more chunks than either previous night, and 85% of
them came back empty. More volume *and* a worse hit rate is the signature of a
silence gate that stopped holding — the phone spent the night uploading an empty
room — and then transmission ceased entirely at 07:58.

I cannot tell from cloud logs alone whether these are causally linked. That needs
the phone.

## 3. Everything the service itself did was clean

| | |
|---|---|
| HTTP requests | 7,274 |
| Errors / warnings | **0** (100% 2xx: 4,852×200, 2,422×201) |
| Chunks transcribed | 2,422 |
| Audio submitted | 27.5 h (median chunk 44.6 s) |
| Transcription latency | median 8.8 s, max 15.3 s per ~45 s chunk (~5× realtime) |
| Languages | mr-IN 1,773 · hi-IN 649 |

| IST day | chunks | audio | active hours |
|---|---|---|---|
| Fri 07 Aug | 1,019 | 11.8 h | 22/24 |
| Sat 08 Aug | 931 | 10.8 h | 23/24 |
| Sun 09 Aug | 472 | 4.9 h | 8/24, then stopped |

Empty rate overall 36.7%, and most of it is genuine silence — it tracks hour of day
hard (0.0% empty at 16:00 with n=133; 90–100% in the 02:00–07:00 band). Marathi
38.6% vs Hindi 31.7%: overnight skews mr-IN (82%) versus daytime (70%), so
composition explains part of that gap but not all of it.

## 4. Real defect: transcripts are unauditable from logs

`vexyl_stt_server.py:286` logs the transcript inline. `logging.basicConfig` sets no
encoding and the Dockerfile sets no `LANG`/`PYTHONIOENCODING`, so Devanagari reaches
Cloud Logging as literal ASCII `?`. Verified via the logging API as JSON (which would
preserve `\uXXXX`): **0 of 2,422 completions contain a single Devanagari codepoint**.

Almost certainly log-rendering loss rather than corrupt output — `job.transcript`
holds a real `str` and `json.dumps` escapes non-ASCII, so the phone likely receives
correct text. But it means transcript *quality* cannot be audited from logs at all.
You can count empties and nothing else.

Fix: `ENV LANG=C.UTF-8 PYTHONIOENCODING=utf-8` in the Dockerfile.

## 5. Checked, and NOT bugs

- **`/batch/result/{id}` got zero calls in two days.** Correct:
  `/batch/status/{id}` returns `transcript` inline on COMPLETED
  (`vexyl_stt_server.py:854`), so the client collects via status. The 2,292 expiry
  cleanups are TTL garbage collection after collection.
- **The SIGTERM.** Cloud Run scale-to-zero, see §1.

## 6. What NOT to do about the empty rate

Do not add a speech-classifier VAD. `AudioChunker`/`VoiceActivityDetector` already
run an adaptive energy gate before whisper, and commit `3440259` records Silero
being wired up end to end and measured *worse* (Hindi word recall 0.385 → 0.231 —
it fights `carry_initial_prompt`).

The open question is the existing energy gate's tuning, not whether to add one.
That same commit notes the gate passing 527.9 s of a 600 s chunk, which is the leak
§2 would be an acute case of. Retune against fixtures with known references, the way
every other quality change in this repo was checked.

---

## Next steps

**Right now:** tap the "Echo is not listening" notification (still in the shade), or
open the app, to resume capture. Nothing is broken — it just needs the tap.

**So this stops costing you a day's recording:**

1. *Make the recovery prompt impossible to miss.* It currently posts on
   `CHANNEL_STATUS` at `IMPORTANCE_DEFAULT` and is silent by default. For the one
   notification that means "your 24/7 recorder is not recording", raise the channel
   importance and make it ongoing/non-dismissible until capture resumes.
2. *Fix the thing that flattened the battery.* §2's 472 overnight chunks are the
   proximate cause of the shutdown. See §6 — retune the existing energy gate; do not
   add a classifier VAD.
3. *Consider a low-battery guard.* Pausing capture below ~10% would have kept the
   phone alive and avoided the reboot entirely, trading the last few percent of
   recording for not losing the following morning.

Note the on-device data dir is unreadable over adb — the release build is not
debuggable, so `run-as` is refused, and `/sdcard/echo.db.bak` is a stale 0-byte file
from the 6 Aug reinstall. Whether chunks are queued locally could not be checked.
Reading it would need a debuggable build.

Read-only throughout. **Never** `connectedAndroidTest` — it wipes the DB, model,
permissions and battery exemption; `adb install -r` only.
