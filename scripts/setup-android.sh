#!/usr/bin/env bash
set -euo pipefail

# Official Android CLI23.0 archive, cross-checked with Google's SDK repository.
# The SHA256 pin is verified before extraction or execution. No third-party setup action.
if [[ $# -eq 0 ]]; then
  echo 'Pass the exact Android SDK packages to install.' >&2
  exit 2
fi
sdk_root="${RUNNER_TEMP:?}/ziphap-android-sdk"
cli_archive="${RUNNER_TEMP}/ziphap-commandline-tools23.zip"
cli_unpack="${RUNNER_TEMP}/ziphap-cli-unpacked"
cli_url='https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip'
cli_sha256='0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8'

if [[ ! -x "$sdk_root/cmdline-tools/latest/bin/android" ]]; then
  curl --proto '=https' --tlsv1.2 --fail --location --retry 3 "$cli_url" -o "$cli_archive"
  printf '%s  %s\n' "$cli_sha256" "$cli_archive" | sha256sum --check --strict
  mkdir -p "$cli_unpack" "$sdk_root/cmdline-tools"
  unzip -q "$cli_archive" -d "$cli_unpack"
  mv "$cli_unpack/cmdline-tools" "$sdk_root/cmdline-tools/latest"
  chmod +x "$sdk_root/cmdline-tools/latest/bin/android"
fi
"$sdk_root/cmdline-tools/latest/bin/android" --no-metrics --sdk="$sdk_root" sdk install "$@"
export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$sdk_root" "$sdk_root" >> "${GITHUB_ENV:?}"
printf '%s\n%s\n' "$sdk_root/cmdline-tools/latest/bin" "$sdk_root/platform-tools" >> "${GITHUB_PATH:?}"
