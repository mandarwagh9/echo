# Echo

A 24/7 ambient audio journal for Android. It listens all day, transcribes what it hears
**entirely on the phone**, deletes each recording as soon as its transcript is safely stored,
and writes up your day at 11 PM.

Built as a personal prototype for a single device — not a shippable product. See
[Privacy and legal](#privacy-and-legal).

---

## What it does

```
mic ──► 10-minute WAV chunks ──► on-device Whisper ──► transcript in SQLite ──► audio deleted
                                                              │
                                                    11:00 PM  ▼
                                                        daily summary
```

- **Records continuously.** A foreground service holds the mic; a dedicated reader thread
  does nothing but drain `AudioRecord`, so disk or CPU stalls can never cost you audio.
- **Chunks are sample-exact.** Rotation happens at exactly 9,600,000 samples without ever
  stopping the recorder, so chunk *N+1* starts on the sample after chunk *N*. No gaps.
- **Transcribes offline.** whisper.cpp compiled for arm64, running on 2–4 threads. Nothing
  is uploaded, ever. The only network call the app makes is the one-time model download.
- **Multilingual.** English, Hindi and Marathi, with per-chunk auto-detection so code-switched
  speech survives.
- **Deletes audio.** The WAV is unlinked only after the transcript commits — never before.
- **Summarises at 11 PM.** Timeline, distinctive topics, names mentioned, language mix, and
  the day's key moments — all computed on-device.

## Requirements

| | |
|---|---|
| Phone | arm64-v8a, Android 10 (API 29) or newer |
| Build | JDK 17, Android SDK 36, NDK 27.1, CMake 3.22 |

## Building

```bash
git clone --recurse-submodules https://github.com/mandarwagh9/echo.git
cd echo
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

whisper.cpp is a submodule pinned to **v1.9.2**; if you already cloned without
`--recurse-submodules`, run `git submodule update --init`.

On Windows, `scripts\verify-on-device.ps1` does the install, the runtime-permission grants and
the battery-optimisation exemption in one step.

The release build is signed with the standard debug keystore so it installs directly. Native
code is compiled `-O3` in **both** build types — a stock debug build compiles ggml at `-O0`,
which is far too slow to keep up with realtime.

## First run

1. Grant microphone + notification permissions.
2. **Settings → Speech model → Download.** `Base` (57 MB) is the right default; `Tiny` is
   faster but noticeably weaker on Hindi and Marathi.
3. Tap the record button.

To see the full pipeline without waiting ten minutes, set **Settings → Capture → Chunk
length → 1 minute**.

## Status

Working prototype. **On a Pixel 9 (Android 17):** 42 JVM unit tests and 11 instrumented tests
passing, plus the throughput measurement below. **On an Android 16 emulator:** a live recording
run confirming sample-exact chunking and audio deletion, and the 11 PM summary chain fired end
to end — neither of which has been repeated on the phone yet. Full record, including the eleven
bugs the process surfaced and an explicit list of what remains unverified, is in
[docs/VERIFICATION.md](docs/VERIFICATION.md).

**Throughput on the Pixel 9: 5.3× realtime** with the shipping **Base** model on 4 threads —
66 s of audio transcribed in 12.5 s, which puts a full 10-minute chunk at about **113 seconds**.
That is the number that decides whether a 24/7 app can drain its queue, and it has a comfortable
margin. (The emulator's 0.48× was Tiny on an x86_64 CPU without AVX2 and was never meaningful.)

## Design notes

The full rationale is in [docs/SYSTEM_DESIGN.md](docs/SYSTEM_DESIGN.md). The decisions worth
knowing up front:

**Recording beats transcription; transcription beats disk.** If the phone falls behind, the
app keeps recording and lets the queue grow. Audio is never deleted on an unproven path — a
chunk that fails to transcribe three times is marked `FAILED` and **keeps its audio** so it
can be retried.

**`MIC`, not `VOICE_RECOGNITION`.** `VOICE_RECOGNITION` applies near-field AGC and noise
suppression tuned for a phone held to your face, which actively damages far-field room
capture. It is also the source most aggressively yielded to the assistant on many devices.

**A VAD gate runs before Whisper.** Most of a real day is silence, and Whisper on near-silence
reliably hallucinates ("Thank you.", subtitle credits). Skipping silent chunks removes a whole
class of garbage and saves a lot of battery.

**Exact alarms, not `WorkManager`, for 11 PM.** Periodic work has a ~15-minute flex window and
will not land on the hour. The alarm hands off to the already-running foreground service, so
Doze cannot defer the work the way it would defer a queued job.

**Reboot needs a tap.** Android does not permit a *microphone* foreground service to be started
from `BOOT_COMPLETED` — mic and camera are while-in-use permissions, explicitly excluded from
the background-start exemptions. After a reboot Echo posts a "tap to resume" notification.
Anything else would fail silently.

## Known limitations

- **Reboot does not auto-resume** (above). This is an OS constraint, not an oversight.
- **The mic is exclusive.** A phone call, the assistant, or another recording app takes the
  microphone away. Echo detects this and retries with backoff, but audio during that window
  is genuinely lost.
- **Name detection is weak.** It keys off capitalisation, so it works in English and misses
  Hindi and Marathi names entirely. The UI labels it approximate rather than pretending.
- **OEM battery managers** (Xiaomi, Samsung, OnePlus) may kill long-running services. Mark
  Echo as unrestricted in battery settings.
- **Small model quality.** `Base` on accented or noisy far-field Marathi is imperfect. `Small`
  is better but may not sustain faster-than-realtime transcription on mid-range hardware.
- **Hindi and Marathi are not yet measured end-to-end.** Every transcription that has actually
  been verified used English audio; the Devanagari tests check the tokeniser, not the
  recogniser. Tests that close this gap are written but have not completed a run — see
  [Not yet verified](docs/VERIFICATION.md#not-yet-verified).

## Privacy and legal

Audio and transcripts never leave the device. Storage is app-private, cloud backup is disabled,
and "Delete everything" really does wipe the database and files.

That does not make this app safe to use casually. **It records people who have not consented.**
India's DPDP Act governs processing others' personal data; using this in a two-party-consent
jurisdiction or anywhere under GDPR is a live legal problem. The persistent notification and
always-visible recording state are deliberate — they are the only honest signal the people
around you get.

## Layout

```
app/src/main/
  cpp/                 JNI bridge + CMake for whisper.cpp
  java/com/mandar/echo/
    audio/             capture, chunking, WAV, VAD, foreground service
    stt/               whisper engine, model download, transcription pipeline
    data/              Room entities, DAOs, settings
    summary/           summariser, TextRank, scheduling
    ui/                Compose screens, monochrome design system
app/src/test/          JVM unit tests
third_party/whisper.cpp
```

## Licence

Personal project. whisper.cpp is MIT, © Georgi Gerganov.
