#!/usr/bin/env bash
set -euo pipefail

project="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
version=8.11.1
wrapper_sha=2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046
distribution_sha=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6

stage="$(mktemp -d)"
trap 'rm -rf -- "$stage"' EXIT
printf "rootProject.name = 'transiva-ci-wrapper'\n" > "$stage/settings.gradle"

# Wrapper generation needs neither the Android build nor a remote URL probe.
# The wrapper still checks the pinned SHA-256 when downloading its distribution.
gradle --project-dir "$stage" wrapper --gradle-version "$version" --distribution-type bin \
  --no-validate-url --gradle-distribution-sha256-sum "$distribution_sha"

wrapper_dir="$stage/gradle/wrapper"
printf '%s  %s\n' "$wrapper_sha" "$wrapper_dir/gradle-wrapper.jar" | sha256sum --check --strict
grep -Fx "distributionUrl=https\\://services.gradle.org/distributions/gradle-${version}-bin.zip" \
  "$wrapper_dir/gradle-wrapper.properties"
grep -Fx "distributionSha256Sum=$distribution_sha" "$wrapper_dir/gradle-wrapper.properties"

install -m 0644 "$wrapper_dir/gradle-wrapper.jar" "$wrapper_dir/gradle-wrapper.properties" "$project/gradle/wrapper/"
install -m 0644 "$stage/gradlew.bat" "$project/gradlew.bat"
install -m 0755 "$stage/gradlew" "$project/gradlew"
