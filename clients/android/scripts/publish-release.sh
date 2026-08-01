#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_repo="kachofugetsu09/akashic-mobile"
version="${1:?usage: publish-release.sh VERSION}"
tag="v$version"
asset="Akashic-Mobile-$tag.apk"
apk="$repo_dir/app/build/outputs/apk/release/app-release.apk"
mapping="$repo_dir/app/build/outputs/mapping/release/mapping.txt"
android_home="${ANDROID_HOME:-$HOME/Android/Sdk}"
apkanalyzer="${AKASHIC_ANDROID_APKANALYZER:-$android_home/cmdline-tools/latest/bin/apkanalyzer}"

"$repo_dir/scripts/build-release.sh"
test -x "$apkanalyzer"
apk_version="$("$apkanalyzer" manifest version-name "$apk")"
if [[ "$version" != "$apk_version" ]]; then
    echo "发布版本 $version 与 APK versionName $apk_version 不一致" >&2
    exit 1
fi

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
asset_path="$temp_dir/$asset"
mapping_path="$temp_dir/mapping-$tag.txt"
checksum_path="$temp_dir/SHA256SUMS-$tag.txt"
cp "$apk" "$asset_path"
cp "$mapping" "$mapping_path"
(
    cd "$temp_dir"
    sha256sum "$asset" >"$(basename "$checksum_path")"
)
gh release create "$tag" "$asset_path" "$mapping_path" "$checksum_path" \
    --repo "$release_repo" \
    --title "Akashic Mobile $tag" \
    --notes "Akashic Android 客户端 $tag"
