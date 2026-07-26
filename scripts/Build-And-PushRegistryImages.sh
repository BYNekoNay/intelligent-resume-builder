#!/usr/bin/env bash
set -euo pipefail

require_value() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: $name" >&2
    exit 2
  fi
}

for variable in ACR_REGISTRY ACR_NAMESPACE ACR_USERNAME ACR_PASSWORD IMAGE_TAG; do
  require_value "$variable"
done

platform="${IMAGE_PLATFORM:-linux/amd64}"
registry_path="${ACR_REGISTRY}/${ACR_NAMESPACE}"

printf '%s' "$ACR_PASSWORD" | docker login "$ACR_REGISTRY" --username "$ACR_USERNAME" --password-stdin

build_and_push() {
  local image_name="$1"
  local dockerfile="$2"
  local context_dir="$3"

  docker buildx build \
    --platform "$platform" \
    --push \
    --file "$dockerfile" \
    --tag "${registry_path}/${image_name}:${IMAGE_TAG}" \
    "$context_dir"
}

build_and_push intelligent-resume-api server/Dockerfile server
build_and_push intelligent-resume-web web/Dockerfile web
build_and_push intelligent-resume-pdf pdf-service/Dockerfile pdf-service
build_and_push intelligent-resume-edge deploy/Dockerfile deploy

# The database image is mirrored by the cloud builder, so ECS never contacts Docker Hub.
docker buildx imagetools create \
  --tag "${registry_path}/intelligent-resume-mysql:${IMAGE_TAG}" \
  mysql:8.4
