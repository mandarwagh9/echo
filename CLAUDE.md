# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Echo is a 24/7 ambient audio recorder for Android (Kotlin/Compose + whisper.cpp via JNI),
targeting **one device** — the developer's Pixel 9. It records continuously, transcribes in
chunks, deletes each WAV only once deletion is proven safe, and summarises the day at 23:00.

Deeper context lives in `docs/`: `SYSTEM_DESIGN.md` (the why, and the queue's invariants),
`VERIFICATION.md` (what was actually measured on hardware), `AUDIT-2026-08-06.md` (a snapshot
audit — several findings have since been fixed, so verify against the tree before acting on it).
Those documents lag the code in places; when they disagree with the source, the source wins.

## Working with the phone

The device holds irreplaceable data — recorded transcripts, the downloaded Whisper model, the
`RECORD_AUDIO` grant, and the battery-optimisation exemption. Two mechanisms destroy it:

- The instrumented suites call `deleteAll()` on the **real** app database
  (`SummaryEngineInstrumentedTest`, `AcousticCaptureInstrumentedTest`).
- Gradle uninstalls both APKs after `connectedAndroidTest` — **including when it fails** — which
  drops the model, the permission grant and the battery exemption along with everything else.

So: **`adb install -r` only.** `scripts\install-on-phone.ps1` is the safe path — it never
uninstalls, never runs a test, and checks the Room migration opened the existing database.
`scripts\verify-on-device.ps1` also grants permissions and the doze whitelist; its `-RunTests`
switch is the destructive one and currently fails the build (four instrumented tests have never
completed a run). To run only the known-good subset:

```
-Pandroid.testInstrumentationRunnerArguments.class=com.mandar.echo.WhisperPipelineInstrumentedTest,com.mandar.echo.SummaryEngineInstrumentedTest
```

## Build and test

Toolchain: JDK 17, compileSdk/targetSdk 36, minSdk 29, NDK `27.1.12297006`, CMake 3.22.1.
whisper.cpp is a submodule — `git submodule update --init` if the clone was flat.

```bash
./gradlew :app:assembleRelease -PechoAbi=arm64-v8a   # phone-only, ~24.5 MB
./gradlew :app:assembleRelease                        # also bundles x86_64 for an emulator
powershell -File scripts\install-on-phone.ps1         # install without losing state

./gradlew :app:testReleaseUnitTest                    # JVM unit tests
./gradlew :app:testReleaseUnitTest --tests "com.mandar.echo.SettleTest"   # one class
```

`testBuildType = "release"`, so there is no `connectedDebugAndroidTest`; instrumented tests
exercise the same `-O3` native build that ships. `:app:test` runs both variants' unit tests and
is usually wasted work.

The instrumented task is `:app:connectedReleaseAndroidTest`, and it is the destructive one — do
not run it against the phone, and if you run it at all, append the known-good class filter above.
Note also that `-PechoAbi=arm64-v8a` produces an APK that cannot run on an x86_64 emulator, which
is the only place the live-run and 23:00-chain verifications have ever succeeded; drop the flag
when building for one.

Native code is compiled `-O3` in **both** build types on purpose — a stock debug build compiles
ggml at `-O0`, which cannot keep up with realtime. The release build is signed with the standard
debug keystore so `assembleRelease` produces a directly installable APK.

`local.properties` (gitignored) supplies `echo.stt.url` / `echo.stt.key` and
`echo.upload.url` / `echo.upload.key` as `BuildConfig` fields. Absent values compile to empty
strings and that backend is simply unusable — so a fresh clone builds fine, it just has nothing
configured.

The GCP side has its own tests, offline and credential-free:

```bash
python -m unittest discover -s gcp/tests -t gcp
./gradlew :app:testReleaseUnitTest --tests '*GcsUploadLiveTest'   # needs ECHO_LIVE_UPLOAD_URL/KEY
```

`GcsUploadLiveTest` is skipped unless those env vars are set. It drives the shipping
`HttpUploadTransport` against the deployed service, which is the only way to catch the things a
fake cannot — that the session-start POST needs an empty body and `x-goog-resumable: start`, that
`setFixedLengthStreamingMode` and the signature agree.

Watch it work: `adb logcat -s RecordingService AudioChunker TranscriptionPipeline CloudTranscriber CloudGate EchoWhisper SummaryScheduler`

## Architecture — the invariants that span files

**Recording outranks everything.** Three threads, and the ordering is a hard rule: the recorder
thread only does `AudioRecord.read()` + `offer()` to a bounded handoff queue — no file I/O, no
DB, no allocation. `AudioRecord`'s ring buffer overwrites *silently* when nobody drains it, so
anything that can block belongs on the writer thread or the transcription coroutine. If the
writer queue saturates, the **newest** buffer is dropped and a counter is surfaced in the UI: a
visible gap, never silent corruption.

**Chunk rotation is sample-exact.** `AudioRecord` is never stopped or restarted during rotation.
The writer counts samples and splits the current buffer at exactly `chunkMinutes × 60 × 16000`
(9,600,000 at the default 10 minutes — the length is a setting, the sample-exactness is not),
patches the closing WAV header, opens the next file. Chunk *N+1* therefore starts on the sample
after chunk *N*, by construction. Any change here must preserve that.

**`TRANSCRIBING` is a lease, not a state.** `chunks.claimedAt` is both the timestamp and the
lease **token**. `claimNext()` is one transaction (`nextClaimableId` → `markClaimed` → `byId`);
every terminal write is `WHERE id = :id AND claimedAt = :lease`, and `settle()` is the single
transactional exit that writes segments + status together, throwing `LeaseLostException` (which
rolls the segments back) if the lease moved. Never add a write path that finishes a chunk
outside `settle()`.

**Parking is not failing.** A chunk waiting on something external — cold server, no radio,
another chunk holding the server's one worker — goes back to `PENDING` with a `notBefore` and
increments `transientFailures`. It must never touch `attempts` (which alone drives
`attempts >= 3 ⇒ FAILED`) and must never fall back to the on-device engine: measured word recall
is 0.23 Hindi / **0.00** Marathi on Whisper Base versus 1.00 for the server, so a fallback
destroys the record *and* then deletes the audio that could have fixed it.

**`audioHold` gates WAV deletion, and null is never permission.** A stored transcript is not on
its own proof the audio is expendable — a multi-piece upload with a rejected piece leaves
`coveredMs < voicedMs` (a hole that looks like success), and a device transcript taken while the
server was merely unavailable is provisional. Both set `audioHold`; only the 1 GB retained-audio
pressure valve overrides it, deliberately and with a log line.

**Cloud uploads are a durable piece machine, not coroutine state.** `cloud_jobs` rows carry
`NEW → SUBMITTED → COMPLETED | REJECTED | LOST` per 120-second piece, cut from the *compacted
voiced stream* rather than wall-clock time. `streamFingerprint` is computed over the exact array
uploaded, so any gate or gain change invalidates stored offsets and forces a replan instead of
relabelling audio that moved underneath its timestamps. `languageSent` is frozen at plan time.
`LOST` and `REJECTED` are deliberately distinct — they want opposite answers about the WAV.
Completion is decided by **key presence**, not `isNotBlank()`: an empty transcript is a success.

**23:00 uses an exact alarm handed to the running foreground service**, not WorkManager (~15-min
flex window; Doze defers queued jobs). If recording is off when it fires, the service promotes as
`dataSync` — the `microphone` FGS type cannot be background-started on Android 14+.

**Reboot cannot auto-resume the mic.** Android excludes mic/camera from background-start
exemptions, so `BootReceiver` posts a "tap to resume" notification instead. This is an OS
constraint; anything else fails silently.

## Three backends, and the state the third one added

`SttBackend` is `ON_DEVICE` (whisper.cpp), `CLOUD` (the self-hosted IndicConformer server), and
`BATCH` (upload to GCP, Chirp 3 transcribes it later). The third is the newest and the one most
likely to surprise you, because it broke the assumption every other path rests on: that a chunk
is claimed, transcribed and settled in one pass. Under batch the transcript arrives hours later,
out of band.

**A chunk in `UPLOADED` still holds its audio, and that is not an oversight.** It carries
`AudioHold.AWAITING_REMOTE` until the transcript comes back. An upload proves a second copy
reached a bucket, not that anything read it — and the bucket deletes its own copy on a lifecycle
timer either way. The rule the rest of the codebase enforces is unchanged and must stay so:
**audio is released when a transcript is stored, never merely when a copy exists elsewhere.**

`UPLOADED` is not terminal (work remains) and not claimable (`nextClaimableId` takes only
`PENDING`). `docs/ARCH-2026-08-10-batch-first.md` §11 lists every query and sweep that filters on
`status` or `audioHold` and what each does with it — adding one state produced seven bugs, found
one per pass, all in the seam between new code and old invariants. Read that table before adding
an eighth.

The generalisation from it is worth carrying into any change here: **predicates in this codebase
tend to name the states they want rather than the property they mean.** `!cloudSelected` meant
"has no remote transcriber"; `status IN ('DONE','SILENT')` meant "settled". Enumerating states is
correct right up until someone adds one.

## The GCP tier lives in `gcp/`

Not a submodule and not deployed from the app — `gcp/deploy.sh` stands it all up and is
idempotent. Live in project `agentbillboard`:

| | |
|---|---|
| `echo-upload` | Cloud Run service. Mints signed resumable-upload URLs and serves `/v1/segments`. **Bytes never pass through it.** |
| `echo-batch` | Cloud Run job on a 15-minute tick: reap finished batches, then submit new audio. Also `--summarise` at 23:03 IST. |
| Buckets | 3-day lifecycle backstop — deliberately longer than dynamic batching's 24-hour SLA, or the backstop races the tier it backs up. |

The separate `vexyl-stt` repo (`C:\Users\Mandar\vexyl-stt`) still serves the `CLOUD` backend and
is unaffected by any of this.

## Room

Schema version 2, `MIGRATION_1_2` additive. Note that adding a `ChunkStatus` value needs no
migration — the column is TEXT and the identity hash covers tables and columns, not enum values —
but it does need the §11 sweep above. `fallbackToDestructiveMigration` is **deliberately
absent** — a schema mismatch crashes rather than silently wiping the user's recordings. There is
no `app/schemas/` export, so any entity change means hand-writing `MIGRATION_2_3` with no golden
schema to diff against; enabling `room.schemaLocation` first is worth doing before that happens.

## The server is a separate repo

The cloud backend is `vexyl-stt` at `C:\Users\Mandar\vexyl-stt` (IndicConformer-600M on Cloud
Run, `asia-south1`) — not a submodule, not discoverable from this tree. `stt/BatchProtocol.kt` is
a pure-function mirror of that server's wire behaviour, diffed field by field and covered by
table tests with no socket. **A change on either side has to be checked against the other**, and
the server's quirks are load-bearing client-side: it caps audio at 300 s, runs one inference
behind a global lock, keeps jobs in an in-process dict (404 on poll means resubmit, bounded), and
returns `error_message` on status but `error` on submit refusals.

## Settled decisions — do not re-litigate

- **No classifier VAD.** Silero was wired end to end and measured *worse* (Hindi recall
  0.385 → 0.231; it fights `carry_initial_prompt`) — commit `3440259`. The energy gate's tuning
  was the open question and is now answered in `VadCalibrationTest`; adding a model still is not.
- **The gate has one entry point and decides on duration, not ratio.**
  `VoiceActivityDetector.analyse` returns the regions, and `hasSpeech`/`speechRatio` are summaries
  of them. It used to expose two tests that answered the same question differently, and a chunk on
  which they disagreed had its transcript destroyed and its WAV released. Do not add a second
  gate. `MIN_VOICED_MS` is absolute on purpose: a bar denominated as a fraction of chunk length
  moves every time chunk length is reconfigured, which is how a lone sentence in a 10-minute chunk
  was deleted as silence.
- **Change a VAD constant only with `VadCalibrationTest` output in front of you.** It reads the
  four real WAV fixtures, mixes them into synthetic noise, and prints the frame-energy
  distribution the constants were picked from. Recall cannot be measured in the field at all — a
  chunk wrongly called silent has its WAV deleted, so there is nothing left to count — which makes
  that file the only check on the direction that loses recordings.
- **Never send English to the server.** `en-IN` is absent from its language map and coerces to
  Malayalam, which returns HTTP 200 with plausible non-empty text — a failure that looks exactly
  like success. `CloudTranscriber.supports` is `hi`/`mr`/`auto` only.
- **`MIC`, not `VOICE_RECOGNITION`.** The latter applies near-field AGC/NS that damages far-field
  room capture, and is the source most aggressively yielded to the assistant.
- **No `material-icons-extended`.** It cost ~30 MB of dex; every glyph is drawn in
  `ui/components/Primitives.kt` (commit `926484d`, 49.9 → 24.5 MB).
- **The temperature-fallback ladder stays capped.** Throughput, not accuracy, is the binding
  constraint — a recorder that transcribes slower than it records has a queue that never drains.

## Repo notes

- `third_party/whisper.cpp` is a vendored submodule, pinned at `306c88f` (v1.9.2 — CMake reads
  the version out of upstream at build time and `getSystemInfo()` reports what the build saw, so
  trust those over any number written down). Do not edit it, and note its `AGENTS.md` /
  `README.md` are upstream's instructions, not this project's.
- The README predates the cloud backend and still says nothing is ever uploaded. The cloud path
  ships, off by default and opt-in; `SettingsScreen` copy has the same drift. Worth fixing, but
  don't take the copy as a statement of behaviour.
- `main` is currently ahead of `origin/main`, so recent work exists on this disk only.
