import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import uuid

spec = importlib.util.spec_from_file_location("aws_harness", Path(__file__).parents[1] / "aws_harness.py")
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.original_root = harness.ROOT
        harness.ROOT = Path(self.temp.name)
        account = "123456789012"
        values = {"media_bucket_name": "fiapx-media-files"}
        for key, name in (("processing_queue_url", "fiapx-processing-work"),
                          ("processing_dlq_url", "fiapx-processing-work-dlq"),
                          ("video_events_queue_url", "fiapx-videos-events"),
                          ("video_events_dlq_url", "fiapx-videos-events-dlq")):
            values[key] = f"https://sqs.us-east-1.amazonaws.com/{account}/{name}"
        values["processing_local_role_arn"] = f"arn:aws:iam::{account}:role/fiapx-processing-local"
        values["video_local_role_arn"] = f"arn:aws:iam::{account}:role/fiapx-video-local"
        self.outputs = {key: {"value": value} for key, value in values.items()}
        self.run = harness.prepare(self.outputs, account)
        _, self.manifest = harness.checked_run(self.run)
        self.owner = str(uuid.uuid4()); self.video = str(uuid.uuid4())
        self.event = {"aggregateId": self.video, "ownerId": self.owner, "eventId": str(uuid.uuid4()),
                      "eventType": "VideoProcessingRequested", "schemaVersion": 1,
                      "payload": {"bucket": "fiapx-media-files", "sizeBytes": 10, "sha256": "a" * 64,
                                  "objectKey": f"originals/{self.owner}/{self.video}/{uuid.uuid4()}"}}

    def tearDown(self):
        harness.ROOT = self.original_root
        self.temp.cleanup()

    def record(self):
        harness.enroll_owner(self.run, self.owner)
        harness.record_request(self.run, self.manifest, json.dumps(self.event))

    def test_prepare_disables_remote_execution_and_keeps_session_credentials_empty(self):
        self.assertIn("PROCESSING_AWS_HARNESS_APPROVED=false", (self.run / "compose.env").read_text())
        self.assertIn("AWS_SESSION_TOKEN=\n", (self.run / "worker.env").read_text())
        self.assertEqual("NOT_EXECUTED", self.manifest["remoteExecution"])

    def test_wrong_account_or_queue_is_rejected_before_any_run_is_created(self):
        before = set(harness.ROOT.iterdir())
        with self.assertRaises(ValueError): harness.prepare(self.outputs, "999999999999")
        self.outputs["processing_queue_url"]["value"] += "-other"
        with self.assertRaises(ValueError): harness.prepare(self.outputs, "123456789012")
        self.assertEqual(before, set(harness.ROOT.iterdir()))

    def test_unknown_owner_or_object_cannot_be_enrolled(self):
        with self.assertRaises(ValueError): harness.record_request(self.run, self.manifest, json.dumps(self.event))
        harness.enroll_owner(self.run, self.owner)
        self.event["payload"]["objectKey"] += "/../../foreign"
        with self.assertRaises(ValueError): harness.record_request(self.run, self.manifest, json.dumps(self.event))
        self.assertFalse((self.run / "control/allowed" / self.video).exists())

    def test_envelope_is_preserved_and_cannot_be_replaced(self):
        self.record()
        self.assertEqual(json.dumps(self.event), (self.run / "requests" / (self.video + ".json")).read_text())
        with self.assertRaises(FileExistsError): harness.record_request(self.run, self.manifest, json.dumps(self.event))

    def test_multiline_envelope_keeps_original_bytes(self):
        harness.enroll_owner(self.run, self.owner)
        raw = json.dumps(self.event, indent=2).replace("\n", "\r\n") + "\r\n"
        harness.record_request(self.run, self.manifest, raw)
        self.assertEqual(raw.encode(), (self.run / "requests" / (self.video + ".json")).read_bytes())

    def test_fault_requires_manifest_and_cannot_be_rearmed(self):
        with self.assertRaises(ValueError): harness.control(self.run, "worker-a", self.video, "after-put", "arm")
        self.record()
        harness.control(self.run, "worker-a", self.video, "after-put", "arm")
        with self.assertRaises(ValueError): harness.control(self.run, "worker-a", self.video, "after-put", "release")
        base = self.run / "control" / f"worker-a.{self.video}.after-put"
        Path(str(base) + ".claimed").touch()
        harness.control(self.run, "worker-a", self.video, "after-put", "release")
        with self.assertRaises(ValueError): harness.control(self.run, "worker-a", self.video, "after-put", "arm")

    def test_run_path_cannot_escape_private_directory(self):
        with self.assertRaises(ValueError): harness.checked_run(self.run.parent)


if __name__ == "__main__":
    unittest.main()
