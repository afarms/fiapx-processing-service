#!/bin/bash
set -euo pipefail
service="${GITHUB_REPOSITORY##*/}"
[[ "$service" =~ ^fiapx-(identity|video|processing)-service$ ]]
[[ "$GITHUB_SHA" =~ ^[a-f0-9]{40}$ ]]
account=$(aws sts get-caller-identity --query Account --output text)
registry="$account.dkr.ecr.us-east-1.amazonaws.com"
image="$registry/$service:$GITHUB_SHA"
workdir=$(mktemp -d)
trap 'rm -rf -- "$workdir"' EXIT
if aws ecr describe-images --repository-name "$service" --image-ids "imageTag=$GITHUB_SHA" > "$workdir/image.json" 2> "$workdir/error"; then
  echo 'Reusing the immutable image for this commit.'
elif grep -q 'ImageNotFoundException' "$workdir/error"; then
  aws ecr get-login-password | docker login --username AWS --password-stdin "$registry"
  docker tag "$service:ci" "$image"
  docker push "$image"
else
  echo 'Unable to query ECR; check permissions and repository.'
  exit 1
fi
digest=$(aws ecr describe-images --repository-name "$service" --image-ids "imageTag=$GITHUB_SHA" --query 'imageDetails[0].imageDigest' --output text)
[[ "$digest" =~ ^sha256:[a-f0-9]{64}$ ]]
printf 'IMAGE_REF=%s/%s@%s\n' "$registry" "$service" "$digest" >> "$GITHUB_ENV"
