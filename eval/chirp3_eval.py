"""Score Chirp 3 on the same fixtures, with the same metric, as everything else.

The question this answers is narrow and load-bearing: Echo runs a self-hosted
IndicConformer-600M on Cloud Run for one reason -- on-device Whisper measured
0.23 word recall on Hindi and 0.00 on Marathi, and IndicConformer measured
1.00 on both. Chirp 3 lists mr-IN as GA. If it holds up, the entire self-hosted
server, its piece state machine, its 300 s cap and its cold starts stop being
necessary. If it does not, the architecture below it can still change but the
model cannot.

So this deliberately reuses `IndicSpeechInstrumentedTest`'s metric verbatim --
fraction of reference words that appear anywhere in the hypothesis -- rather
than a better one. A better metric would produce numbers that cannot be
compared against the ones already recorded in docs/VERIFICATION.md.

WHAT THESE FIXTURES CANNOT TELL YOU: they are 16 kHz mono clips synthesised by
Google Cloud Text-to-Speech. Chirp 3 scoring well on them is partly a test of
Google's ASR against Google's own synthesis, and says very little about
far-field Marathi in a noisy room -- which is the actual workload. Read a good
score here as "not obviously worse than IndicConformer", never as "as good".

Standard tier on purpose, not DYNAMIC_BATCHING: the discounted tier is the
24-hour one, and four short clips are a rounding error either way.

    python eval/chirp3_eval.py
"""

from __future__ import annotations

import re
import sys

from google.api_core.client_options import ClientOptions
from google.cloud import storage
from google.cloud.speech_v2 import SpeechClient
from google.cloud.speech_v2.types import cloud_speech

PROJECT = "agentbillboard"
BUCKET = "agentbillboard-echo-stt-eval"
ASSETS = "app/src/androidTest/assets"

# Chirp 3 is not in every region. Tried in order; the first that answers wins.
LOCATIONS = ["us", "asia-southeast1", "eu"]

# (fixture, language_code, reference). References copied from
# IndicSpeechInstrumentedTest and ModelComparisonInstrumentedTest.
CASES = [
    ("hindi.wav", "hi-IN", "आज दोपहर को मीटिंग थी और हमने प्रोजेक्ट के बारे में बात की।"),
    ("marathi.wav", "mr-IN", "उद्या सकाळी आम्ही ऑफिसमध्ये कॅलिब्रेशनचे काम करणार आहोत."),
    ("codeswitch.wav", "hi-IN",
     "कल का sensor calibration अभी pending है, मैं evening तक update भेज दूंगा।"),
    ("jfk.wav", "en-US",
     "and so my fellow americans ask not what your country can do for you "
     "ask what you can do for your country"),
]

# What is already recorded, for the comparison column. docs/VERIFICATION.md §7/§10.
RECORDED = {
    "hindi.wav": ("0.23", "1.00"),
    "marathi.wav": ("0.00", "1.00"),
    "codeswitch.wav": ("0.08", "0.67"),
    "jfk.wav": ("1.00", "n/a"),
}

SPLIT = re.compile(r"[\s।.,?!]+")


def recall(reference: str, hypothesis: str) -> float:
    """Fraction of the reference's words present in the hypothesis.

    Verbatim from IndicSpeechInstrumentedTest.recall so the numbers compare.
    """
    ref = [w for w in SPLIT.split(reference) if w]
    if not ref:
        return 0.0
    hyp = {w for w in SPLIT.split(hypothesis) if w}
    return sum(1 for w in ref if w in hyp) / len(ref)


def devanagari_ratio(s: str) -> float:
    letters = [c for c in s if c.isalpha()]
    if not letters:
        return 0.0
    return sum(1 for c in letters if "ऀ" <= c <= "ॿ") / len(letters)


def upload_fixtures() -> dict[str, str]:
    gcs = storage.Client(project=PROJECT)
    try:
        bucket = gcs.get_bucket(BUCKET)
    except Exception:
        # Regional, matching the recognizer, so BatchRecognize is not reading
        # across regions for every request.
        bucket = gcs.create_bucket(BUCKET, location="US")
        print(f"created gs://{BUCKET}")

    uris = {}
    for name, _, _ in CASES:
        blob = bucket.blob(f"fixtures/{name}")
        if not blob.exists():
            blob.upload_from_filename(f"{ASSETS}/{name}")
        uris[name] = f"gs://{BUCKET}/fixtures/{name}"
    return uris


def transcribe(client: SpeechClient, location: str, uri: str, language: str) -> str:
    config = cloud_speech.RecognitionConfig(
        auto_decoding_config=cloud_speech.AutoDetectDecodingConfig(),
        language_codes=[language],
        model="chirp_3",
        features=cloud_speech.RecognitionFeatures(enable_automatic_punctuation=True),
    )
    request = cloud_speech.BatchRecognizeRequest(
        recognizer=f"projects/{PROJECT}/locations/{location}/recognizers/_",
        config=config,
        files=[cloud_speech.BatchRecognizeFileMetadata(uri=uri)],
        recognition_output_config=cloud_speech.RecognitionOutputConfig(
            inline_response_config=cloud_speech.InlineOutputConfig(),
        ),
    )
    response = client.batch_recognize(request=request).result(timeout=900)
    result = response.results[uri]
    if result.error and result.error.code:
        raise RuntimeError(f"{result.error.code}: {result.error.message}")
    return " ".join(
        alt.alternatives[0].transcript
        for alt in result.transcript.results
        if alt.alternatives
    ).strip()


def main() -> int:
    uris = upload_fixtures()

    client = None
    location = None
    for candidate in LOCATIONS:
        try:
            c = SpeechClient(
                client_options=ClientOptions(
                    api_endpoint=f"{candidate}-speech.googleapis.com"
                )
            )
            # Cheapest possible probe that proves chirp_3 exists here.
            transcribe(c, candidate, uris["jfk.wav"], "en-US")
            client, location = c, candidate
            break
        except Exception as exc:  # noqa: BLE001 - report and try the next region
            print(f"  {candidate}: unavailable ({type(exc).__name__}: {exc})")

    if client is None:
        print("chirp_3 answered in none of: " + ", ".join(LOCATIONS))
        return 1

    print(f"\nChirp 3 via {location}-speech.googleapis.com\n")
    header = f"{'fixture':<16}{'lang':<8}{'recall':>8}{'devan':>8}   {'whisper':>8}{'indic':>8}"
    print(header)
    print("-" * len(header))

    rows = []
    for name, language, reference in CASES:
        try:
            text = transcribe(client, location, uris[name], language)
        except Exception as exc:  # noqa: BLE001 - a failure is a result
            print(f"{name:<16}{language:<8}{'ERROR':>8}   {exc}")
            continue
        rec = recall(reference, text)
        dev = devanagari_ratio(text)
        whisper, indic = RECORDED[name]
        print(f"{name:<16}{language:<8}{rec:>8.2f}{dev:>8.2f}   {whisper:>8}{indic:>8}")
        rows.append((name, reference, text))

    print("\nTranscripts\n" + "-" * 11)
    for name, reference, text in rows:
        print(f"\n{name}\n  ref: {reference}\n  got: {text}")

    print(
        "\nFixtures are Google TTS synthesis, not far-field room audio. Treat a good"
        "\nscore as 'not obviously worse than IndicConformer', not as 'as good'."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
