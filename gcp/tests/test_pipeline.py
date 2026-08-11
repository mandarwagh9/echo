"""Tests for the parts of the batch pipeline that decide whether audio dies.

Both of this pipeline's real bugs were found by running it, not by reading it,
and both were in here. These pin them.

    python -m unittest discover -s gcp/tests -t gcp
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from echo_batch import pipeline  # noqa: E402


class FakeBlob:
    def __init__(self, payload: str):
        self._payload = payload

    def download_as_text(self) -> str:
        return self._payload


class FakeBucket:
    def __init__(self, blobs: dict[str, FakeBlob]):
        self._blobs = blobs

    def blob(self, path: str) -> FakeBlob:
        if path not in self._blobs:
            raise FileNotFoundError(path)
        return self._blobs[path]


class FakeGcs:
    def __init__(self, buckets: dict[str, FakeBucket]):
        self._buckets = buckets

    def bucket(self, name: str) -> FakeBucket:
        return self._buckets[name]


class FileResult:
    """Enough of BatchRecognizeFileResult for the reader."""

    def __init__(self, uri: str = "", transcript=None):
        self.uri = uri
        self.transcript = transcript


def results_json(*alternatives) -> str:
    return json.dumps({
        "results": [
            {
                "languageCode": lang,
                "alternatives": [{"transcript": text, "words": words}],
            }
            for text, lang, words in alternatives
        ]
    })


class SegmentReading(unittest.TestCase):
    """The bug that stored zero segments and deleted the audio anyway."""

    def test_transcript_is_read_from_the_gcs_output_not_the_inline_field(self):
        # Under GcsOutputConfig the inline transcript is empty and the payload
        # lives at file_result.uri. Reading the inline field finds nothing, which
        # is indistinguishable from silence -- and silence is a settled answer
        # that releases the recording.
        payload = results_json(
            ("उद्या सकाळी काम", "mr", [
                {"endOffset": "0.280s", "word": "उद्या"},
                {"startOffset": "0.280s", "endOffset": "3.360s", "word": "काम"},
            ]),
        )
        gcs = FakeGcs({"results": FakeBucket({"out/x_transcript_1.json": FakeBlob(payload)})})

        segments = pipeline._segments_from(
            gcs, FileResult(uri="gs://results/out/x_transcript_1.json")
        )

        self.assertEqual(1, len(segments))
        self.assertEqual("उद्या सकाळी काम", segments[0]["text"])
        self.assertEqual("mr", segments[0]["language"])

    def test_a_result_with_no_uri_raises_rather_than_reading_as_silence(self):
        # The caller uses "did this parse" to decide whether the recording is
        # expendable, so an unreadable result must be loud, not empty.
        with self.assertRaises(RuntimeError):
            pipeline._segments_from(FakeGcs({}), FileResult(uri=""))

    def test_an_unreachable_output_object_raises(self):
        gcs = FakeGcs({"results": FakeBucket({})})
        with self.assertRaises(Exception):
            pipeline._segments_from(gcs, FileResult(uri="gs://results/out/missing.json"))

    def test_a_genuinely_empty_transcript_yields_no_segments_without_raising(self):
        # A quiet recording is a real answer. It must read cleanly and produce
        # nothing, which is what lets the caller settle and release it.
        gcs = FakeGcs({"r": FakeBucket({"out/e.json": FakeBlob(json.dumps({"results": []}))})})
        self.assertEqual([], pipeline._segments_from(gcs, FileResult(uri="gs://r/out/e.json")))


class Timings(unittest.TestCase):
    """The bug that put a whole day at midnight."""

    def test_offsets_come_from_the_words(self):
        # There is no resultEndOffset in this payload -- only alternatives and
        # languageCode -- so reading for one produced 0..0 for every segment.
        payload = results_json(
            ("a b", "hi", [
                {"endOffset": "0.280s", "word": "a"},
                {"startOffset": "0.280s", "endOffset": "3.360s", "word": "b"},
            ]),
        )
        gcs = FakeGcs({"r": FakeBucket({"out/t.json": FakeBlob(payload)})})
        seg = pipeline._segments_from(gcs, FileResult(uri="gs://r/out/t.json"))[0]

        # The first word omits startOffset entirely when it begins at zero.
        self.assertEqual(0, seg["startMs"])
        self.assertEqual(3360, seg["endMs"])

    def test_duration_parsing(self):
        self.assertEqual(280, pipeline._offset_ms("0.280s"))
        self.assertEqual(3000, pipeline._offset_ms("3s"))
        self.assertEqual(0, pipeline._offset_ms(None))
        self.assertEqual(0, pipeline._offset_ms(""))


class ObjectNaming(unittest.TestCase):
    """The wall-clock anchor a transcript gets, and nothing else gives it."""

    def test_the_epoch_is_read_from_the_object_name(self):
        self.assertEqual(
            1786358637983,
            pipeline._chunk_start_ms("gs://b/pending/1786358637983.wav"),
        )

    def test_a_name_that_is_not_an_epoch_has_no_anchor(self):
        # None, not zero. Zero would place the recording in 1970 rather than
        # admitting there is no anchor.
        self.assertIsNone(pipeline._chunk_start_ms("gs://b/pending/marathi.wav"))

    def test_the_day_is_local_not_utc(self):
        # 2026-08-10 00:30 IST is 2026-08-09 19:00 UTC. A UTC day boundary would
        # file the small hours of every morning under the previous day.
        just_after_midnight_ist = 1786385400000
        self.assertEqual("2026-08-10", pipeline._day_of(just_after_midnight_ist))


class DeletionRule(unittest.TestCase):
    """Audio dies when a transcript is stored, and at no other time."""

    def test_release_drops_the_claim_so_a_later_pass_retries(self):
        # An unreadable result must hand the recording back, not strand it. The
        # claim is the only thing stopping the next pass picking it up.
        blob = mock.MagicMock()
        blob.exists.return_value = True
        blob.metadata = {pipeline.config.CLAIM_KEY: "op/1", "other": "kept"}
        bucket = mock.MagicMock()
        bucket.name = "ingest"
        bucket.blob.return_value = blob

        pipeline._release(bucket, ["gs://ingest/pending/1.wav"])

        self.assertNotIn(pipeline.config.CLAIM_KEY, blob.metadata)
        self.assertEqual("kept", blob.metadata["other"])
        blob.patch.assert_called_once()
        blob.delete.assert_not_called()

    def test_delete_removes_only_objects_that_are_there(self):
        gone, present = mock.MagicMock(), mock.MagicMock()
        gone.exists.return_value = False
        present.exists.return_value = True
        bucket = mock.MagicMock()
        bucket.name = "ingest"
        bucket.blob.side_effect = [gone, present]

        pipeline._delete(bucket, ["gs://ingest/pending/1.wav", "gs://ingest/pending/2.wav"])

        gone.delete.assert_not_called()
        present.delete.assert_called_once()

    def test_blob_paths_are_derived_from_the_uri(self):
        bucket = mock.MagicMock()
        bucket.name = "ingest"
        pipeline._blobs_for(bucket, ["gs://ingest/pending/1.wav", "gs://other/pending/2.wav"])
        # Only this bucket's objects are addressed; a foreign uri is ignored
        # rather than mangled into a local path.
        bucket.blob.assert_called_once_with("pending/1.wav")


if __name__ == "__main__":
    unittest.main()
