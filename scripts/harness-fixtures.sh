#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
run="${HARNESS_RUN_DIRECTORY:?Use the directory printed by aws_harness.py prepare}"
[[ -f "$run/manifest.json" && -d "$run/fixtures" ]] || { echo 'Prepare the run first' >&2; exit 1; }
# No network or AWS credentials. -n refuses to overwrite a previously reviewed fixture.
for spec in 'concurrent:30:1280x720' 'too-long:301:64x48'; do
    IFS=: read -r name duration dimensions <<< "$spec"
    MSYS_NO_PATHCONV=1 docker run --rm --network none --cpus=2 --memory=1g \
      --mount "type=bind,source=$run/fixtures,target=/fixtures" \
      --entrypoint /usr/bin/ffmpeg fiapx-processing-aws-harness:local \
      -nostdin -hide_banner -loglevel error -n -f lavfi -i "testsrc2=size=$dimensions:rate=10" \
      -t "$duration" -c:v libx264 -threads 2 -pix_fmt yuv420p "/fixtures/$name.mp4"
done
