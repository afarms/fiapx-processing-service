#!/bin/bash
# Output goes directly to kubectl stdin. Never enable shell tracing.
set +x
set -euo pipefail
service="${GITHUB_REPOSITORY##*/}"
case "$service" in
  fiapx-identity-service) database=fiapx_identity ;;
  fiapx-video-service) database=fiapx_video ;;
  fiapx-processing-service) database=fiapx_processing ;;
  *) exit 1 ;;
esac
secret_json=$(aws secretsmanager get-secret-value --secret-id fiapx/runtime --query SecretString --output text)
DB_USERNAME_B64=$(jq -er --arg db "$database" '.database[$db].username | select(type == "string" and length > 0) | @base64' <<< "$secret_json")
DB_PASSWORD_B64=$(jq -er --arg db "$database" '.database[$db].password | select(type == "string" and length > 0) | @base64' <<< "$secret_json")
export DB_USERNAME_B64 DB_PASSWORD_B64
if [[ "$service" != fiapx-processing-service ]]; then
  IDENTITY_SERVICE_KEY_B64=$(jq -er '.identity_service_key | select(type == "string" and length > 0) | @base64' <<< "$secret_json")
  JWT_PUBLIC_KEY_B64=$(jq -er '.jwt.public_key | select(type == "string" and length > 0) | @base64' <<< "$secret_json")
  export IDENTITY_SERVICE_KEY_B64 JWT_PUBLIC_KEY_B64
fi
if [[ "$service" == fiapx-identity-service ]]; then
  JWT_PRIVATE_KEY_B64=$(jq -er '.jwt.private_key | select(type == "string" and length > 0) | @base64' <<< "$secret_json")
  export JWT_PRIVATE_KEY_B64
fi
unset secret_json
envsubst '${DB_USERNAME_B64} ${DB_PASSWORD_B64} ${IDENTITY_SERVICE_KEY_B64} ${JWT_PUBLIC_KEY_B64} ${JWT_PRIVATE_KEY_B64}' < k8s/secrets.yaml
