# Echo

A 24/7 ambient audio journal for Android. It listens all day, transcribes what it hears,
deletes each recording as soon as its transcript is safely stored, and writes up your day at
11 PM.

Transcription runs **on the phone by default**. Two backends that send audio off it are
available and off until chosen — see [Transcription backends](#transcription-backends). That
choice is the most consequential setting in the app.

Built first for a single device, now in a **public sideload beta (0.9.0)**. Read
[Privacy and legal](#privacy-and-legal) before you install it: this app records the people
around you, and that is your responsibility rather than the app's.

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
- **Transcribes offline by default.** whisper.cpp compiled for arm64, running on 2–4 threads.
  On the default backend the only network call the app makes is the one-time model download.
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

## Installing

Download the APK from [Releases](https://github.com/mandarwagh9/echo/releases), allow your
browser to install unknown apps when Android asks, and open it. Echo walks you through the rest.

The app needs three things and asks for all of them during setup:

| | |
|---|---|
| Microphone | The whole app. Nothing works without it. |
| Notifications | The ongoing notification is the only always-visible sign that a recorder is running, and the alert channel is how Echo tells you capture has stopped. |
| Background running | Android suspends apps once the screen has been off a while. Without the exemption, recording stops some time after you put the phone down. **This is the step people skip and then report as a bug.** |

Then it downloads a speech model, once, and works offline afterwards.

## Building

```bash
git clone --recurse-submodules https://github.com/mandarwagh9/echo.git
cd echo
./gradlew :app:assembleRelease -PechoAbi=arm64-v8a
adb install -r app/build/outputs/apk/release/app-release.apk
```

That is a **personal** build: it reads the endpoints in `local.properties` and is signed with
the Android debug key. An APK meant for anyone else is built with

```bash
./gradlew :app:assembleRelease -PechoDistribution=public -PechoAbi=arm64-v8a
```

which compiles those endpoints out entirely and signs with the upload key from
`keystore.properties` (`scripts/make-release-key.ps1` creates it, once). The build refuses to
produce a public APK without that keystore, so the secret-free artifact and the debug-signed one
cannot be the same file.

`-PechoAbi=arm64-v8a` builds for phones only — 24.5 MB. Omit it to also bundle `x86_64` for
emulator testing, at 28.8 MB.

whisper.cpp is a submodule pinned to **v1.9.2**; if you already cloned without
`--recurse-submodules`, run `git submodule update --init`.

On Windows, `scripts\verify-on-device.ps1` does the install, the runtime-permission grants and
the battery-optimisation exemption in one step.

The release build is signed with the standard debug keystore so it installs directly. Native
code is compiled `-O3` in **both** build types — a stock debug build compiles ggml at `-O0`,
which is far too slow to keep up with realtime.

## First run

Setup covers consent, the three permissions above, and the model download. `Base` (57 MB) is
the right default; `Tiny` is faster but noticeably weaker on Hindi and Marathi.

To watch the whole pipeline without waiting ten minutes, set **Settings → Advanced → Recording
length → 1 min**.

## Status

Public beta, 0.9.0. The interface was rebuilt for people who did not write the app, and battery
behaviour was reworked (the wake lock now follows capture rather than the service, and work that
exists only to feed the UI stops when no UI is on screen). **Those battery changes have not yet
been measured on hardware** — there is no `batterystats` before-and-after, only the reasoning.

Underneath, the pipeline is the same one described below. **On a Pixel 9 (Android 17):** the JVM suite and 11 instrumented tests
passing, plus the throughput measurement below. **On an Android 16 emulator:** a live recording
run confirming sample-exact chunking and audio deletion, and the 11 PM summary chain fired end
to end — neither of which has been repeated on the phone yet. Full record, including the eleven
bugs the process surfaced and an explicit list of what remains unverified, is in
[docs/VERIFICATION.md](docs/VERIFICATION.md).

**Throughput on the Pixel 9: 5.3× realtime** with the shipping **Base** model on 4 threads —
66 s of audio transcribed in 12.5 s, which puts a full 10-minute chunk at about **113 seconds**.
That is the number that decides whether a 24/7 app can drain its queue, and it has a comfortable
margin. (The emulator's 0.48× was Tiny on an x86_64 CPU without AVX2 and was never meaningful.)

## Transcription backends

Three, chosen in Settings. Only the first is a default.

| | Where | Hindi / Marathi | Trade |
|---|---|---|---|
| **On device** | whisper.cpp, this phone | 0.23 / **0.00** | Nothing leaves. Cannot do Devanagari. |
| **Your server** | self-hosted IndicConformer-600M | 1.00 / 1.00 | Audio uploaded to a server you run. |
| **Batch (Chirp 3)** | Google Cloud, `gcp/` | 1.00 / 1.00 | Audio uploaded, transcribed later, cheapest. |

Word recall against known references — see [docs/VERIFICATION.md](docs/VERIFICATION.md) for the
on-device numbers and [eval/chirp3_eval.py](eval/chirp3_eval.py) for Chirp 3's. **Every one of
those figures is from clips synthesised by text-to-speech, not far-field room audio**, so read
them as a floor on how bad a model is, not a promise of how good.

Marathi at 0.00 is why the other two exist. It is not "worse", it is nothing: a day of Marathi
through Whisper Base produces a transcript that is not a transcript.

**The batch backend keeps your recording on the phone until its transcript comes back.** An
upload proves a copy reached a bucket, not that anything read it. See
[docs/ARCH-2026-08-10-batch-first.md](docs/ARCH-2026-08-10-batch-first.md).

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
- **Base is weak on Devanagari.** This is the honest headline limitation. On clean, studio-clean
  Hindi it returns correct script and roughly the right words; on far-field Marathi in a noisy
  room it returns correct script and approximately the right *sounds*. `Small` is meaningfully
  better and roughly three times the compute — see
  [docs/VERIFICATION.md](docs/VERIFICATION.md) for the measured comparison.
- **Throughput is the binding constraint, not accuracy.** Base runs at 5.3× realtime on clean
  English and collapses on hard audio, because the temperature-fallback ladder retries every
  window that looks degenerate. A 24/7 recorder that transcribes slower than it records has a
  queue that never drains, so the ladder is deliberately capped.

## Privacy and legal

**On the default backend**, audio and transcripts never leave the device, and the other two
backends are opt-in rather than defaults. The public build ships with no server of its own:
those two backends do nothing until you point them at something you run yourself. Storage is app-private, cloud backup is disabled,
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
eval/                  chirp3_eval.py — scores a backend on the shared fixtures
gcp/                   the batch tier: upload service, batch job, nightly summary
third_party/whisper.cpp
```

## Licence

Personal project. whisper.cpp is MIT, © Georgi Gerganov.
