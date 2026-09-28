"""Offline manifest and fault controls. Does not call AWS, upload, consume or delete data."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import secrets
import uuid

ROOT = Path(__file__).resolve().parents[1] / ".local" / "aws-harness"
POINTS = ("before-download", "before-media", "after-put", "before-ack")


def write_json(path, value):
    # Exclusive creation prevents replacing the reviewable run or request manifest.
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write("\n")


def validate_outputs(outputs, account):
    if not re.fullmatch(r"[0-9]{12}", account):
        raise ValueError("Expected a verified twelve-digit account ID")
    values = {key: value["value"] for key, value in outputs.items()}
    for key, name in (("processing_queue_url", "fiapx-processing-work"),
                      ("processing_dlq_url", "fiapx-processing-work-dlq"),
                      ("video_events_queue_url", "fiapx-videos-events"),
                      ("video_events_dlq_url", "fiapx-videos-events-dlq")):
        if values.get(key) != f"https://sqs.us-east-1.amazonaws.com/{account}/{name}":
            raise ValueError(f"Unexpected Terraform output: {key}")
    for key, role in (("processing_local_role_arn", "fiapx-processing-local"),
                      ("video_local_role_arn", "fiapx-video-local")):
        if values.get(key) != f"arn:aws:iam::{account}:role/{role}":
            raise ValueError(f"Unexpected Terraform output: {key}")
    if values.get("media_bucket_name") != "fiapx-media-files":
        raise ValueError("Unexpected bucket")
    return values


def prepare(outputs, account):
    values = validate_outputs(outputs, account)
    run_id = "fiapx-aws-" + uuid.uuid4().hex[:12]
    run = ROOT / run_id
    run.mkdir(parents=True, exist_ok=False)
    for directory in ("requests", "owners", "control/allowed", "control/events", "fixtures"):
        (run / directory).mkdir(parents=True)
    write_json(run / "manifest.json", {
        "runId": run_id, "account": account, "region": "us-east-1", "resources": values,
        "services": ["processing-db", "video-db", "video", "worker-a", "worker-b"],
        "remoteExecution": "NOT_EXECUTED", "cleanup": "Manual review of recorded objects required",
    })
    # Secrets are local and excluded from both Git and Docker build context.
    (run / "compose.env").write_text(
        f"HARNESS_RUN_ID={run_id}\nHARNESS_RUN_DIRECTORY={run.as_posix()}\n"
        f"HARNESS_PROCESSING_DB_PASSWORD={secrets.token_hex(24)}\n"
        f"HARNESS_VIDEO_DB_PASSWORD={secrets.token_hex(24)}\n"
        f"HARNESS_BUCKET={values['media_bucket_name']}\n"
        f"HARNESS_WORK_QUEUE={values['processing_queue_url']}\n"
        f"HARNESS_RESULT_QUEUE={values['video_events_queue_url']}\n"
        "PROCESSING_AWS_HARNESS_APPROVED=false\n", encoding="utf-8")
    credentials = "AWS_ACCESS_KEY_ID=\nAWS_SECRET_ACCESS_KEY=\nAWS_SESSION_TOKEN=\nAWS_EC2_METADATA_DISABLED=true\n"
    (run / "worker.env").write_text(credentials, encoding="utf-8")
    (run / "video.env").write_text(credentials + "IDENTITY_URL=http://host.docker.internal:8081\nIDENTITY_SERVICE_KEY=\n", encoding="utf-8")
    (run / "fixtures/invalid.mp4").write_bytes(b"FIAP X synthetic invalid media\n")
    return run


def checked_run(path):
    run = path.resolve()
    if run.parent != ROOT.resolve() or not re.fullmatch(r"fiapx-aws-[0-9a-f]{12}", run.name):
        raise ValueError("Run must be inside this checkout's .local/aws-harness")
    manifest = json.loads((run / "manifest.json").read_text(encoding="utf-8"))
    if manifest["runId"] != run.name:
        raise ValueError("Run identity mismatch")
    return run, manifest


def enroll_owner(run, owner):
    owner = str(uuid.UUID(owner))
    write_json(run / "owners" / (owner + ".json"), {"ownerId": owner})


def record_request(run, manifest, raw):
    event = json.loads(raw)
    video, owner, event_id = (str(uuid.UUID(event[key])) for key in ("aggregateId", "ownerId", "eventId"))
    if not (run / "owners" / (owner + ".json")).is_file():
        raise ValueError("Enroll the test owner first")
    payload = event["payload"]
    if event["eventType"] != "VideoProcessingRequested" or event["schemaVersion"] != 1:
        raise ValueError("Unexpected request type/version")
    if payload["bucket"] != manifest["resources"]["media_bucket_name"]:
        raise ValueError("Unexpected request bucket")
    prefix = f"originals/{owner}/{video}/"
    if not payload["objectKey"].startswith(prefix):
        raise ValueError("Request object does not belong to this owner/video")
    uuid.UUID(payload["objectKey"][len(prefix):])
    if not re.fullmatch(r"[0-9a-f]{64}", payload["sha256"]) or not 0 < payload["sizeBytes"] <= 100_000_000:
        raise ValueError("Invalid media metadata")
    # Preserve the byte-identical envelope for redelivery, separate from its index.
    request_path = run / "requests" / (video + ".json")
    with request_path.open("x", encoding="utf-8", newline="") as stream:
        stream.write(raw)
    write_json(run / "requests" / (video + ".index.json"), {
        "videoId": video, "ownerId": owner, "eventId": event_id,
        "originalKey": payload["objectKey"], "envelopeSha256": hashlib.sha256(raw.encode()).hexdigest(),
    })
    (run / "control/allowed" / video).touch(exist_ok=False)
    return video


def control(run, worker, video, point, action, mode="pause"):
    video = str(uuid.UUID(video))
    if worker not in ("worker-a", "worker-b") or point not in POINTS:
        raise ValueError("Unknown worker or fault boundary")
    if not (run / "control/allowed" / video).is_file():
        raise ValueError("Record this video's exact request before arming a fault")
    base = run / "control" / f"{worker}.{video}.{point}"
    if action == "arm":
        if mode not in ("pause", "fail") or any(Path(str(base) + suffix).exists() for suffix in (".claimed", ".release")):
            raise ValueError("Fault already used or invalid mode; use a fresh video/run")
        with Path(str(base) + ".arm").open("x", encoding="utf-8") as stream:
            stream.write(mode)
    else:
        if not Path(str(base) + ".claimed").is_file():
            raise ValueError("Boundary not yet claimed")
        Path(str(base) + ".release").touch(exist_ok=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("prepare")
    create.add_argument("--outputs", type=Path, required=True)
    create.add_argument("--account", required=True)
    owner = sub.add_parser("owner")
    owner.add_argument("--run", type=Path, required=True); owner.add_argument("--id", required=True)
    record = sub.add_parser("record-request")
    record.add_argument("--run", type=Path, required=True); record.add_argument("--file", type=Path, required=True)
    for name in ("arm", "release"):
        command = sub.add_parser(name)
        command.add_argument("--run", type=Path, required=True)
        command.add_argument("--worker", choices=("worker-a", "worker-b"), required=True)
        command.add_argument("--video", required=True)
        command.add_argument("--point", choices=POINTS, required=True)
        if name == "arm": command.add_argument("--mode", choices=("pause", "fail"), default="pause")
    args = parser.parse_args()
    if args.command == "prepare":
        print(prepare(json.loads(args.outputs.read_text(encoding="utf-8-sig")), args.account))
    else:
        run, manifest = checked_run(args.run)
        if args.command == "owner": enroll_owner(run, args.id)
        elif args.command == "record-request":
            with args.file.open(encoding="utf-8", newline="") as stream:
                print(record_request(run, manifest, stream.read()))
        else: control(run, args.worker, args.video, args.point, args.command, getattr(args, "mode", "pause"))


if __name__ == "__main__":
    main()
