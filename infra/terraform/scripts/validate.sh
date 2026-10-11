#!/usr/bin/env sh
# Static quality gate for infra/terraform - needs Docker, NOT AWS credentials:
#   1. terraform fmt -check
#   2. per environment: init (no backend) -> validate -> test (mocked AWS provider)
#   3. tflint (terraform + aws rulesets)
#   4. checkov
# Usage: infra/terraform/scripts/validate.sh  (from any directory, Linux/macOS/Git Bash)
set -eu

TF_IMAGE=${TF_IMAGE:-hashicorp/terraform:1.16.5}
TFLINT_IMAGE=${TFLINT_IMAGE:-ghcr.io/terraform-linters/tflint:v0.64.0}
CHECKOV_IMAGE=${CHECKOV_IMAGE:-bridgecrew/checkov:3.3.26}
ENVIRONMENTS=${ENVIRONMENTS:-"dev prod"}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# Docker Desktop on Windows (Git Bash) needs a native path and no MSYS path mangling.
HOST_ROOT=$( (cd "$ROOT" && pwd -W) 2>/dev/null || echo "$ROOT")
export MSYS_NO_PATHCONV=1

terraform() {
  workdir=$1
  shift
  docker run --rm \
    -v "$HOST_ROOT:/workspace" -w "/workspace/$workdir" \
    -v tf-plugin-cache:/plugin-cache -e TF_PLUGIN_CACHE_DIR=/plugin-cache \
    "$TF_IMAGE" "$@"
}

tflint() {
  docker run --rm \
    -v "$HOST_ROOT:/data" -w /data \
    -v tflint-plugins:/plugins -e TFLINT_PLUGIN_DIR=/plugins \
    "$TFLINT_IMAGE" --config /data/.tflint.hcl "$@"
}

echo "==> terraform fmt"
terraform . fmt -check -recursive -diff

for environment in $ENVIRONMENTS; do
  echo "==> envs/$environment: init / validate / test"
  terraform "envs/$environment" init -backend=false -input=false -no-color >/dev/null
  terraform "envs/$environment" validate -no-color
  terraform "envs/$environment" test -no-color
done

echo "==> tflint"
tflint --init
tflint --recursive --format compact

echo "==> checkov"
docker run --rm -v "$HOST_ROOT:/tf" "$CHECKOV_IMAGE" \
  -d /tf --framework terraform --skip-path .terraform --quiet --compact

echo "All infrastructure checks passed."
