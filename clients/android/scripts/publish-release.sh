#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_repo="kachofugetsu09/akashic-mobile"
version="${1:?usage: publish-release.sh VERSION [--prerelease]}"
release_mode="${2:-}"
if [[ -n "$release_mode" && "$release_mode" != "--prerelease" ]]; then
    echo "未知发布模式: $release_mode" >&2
    exit 1
fi
tag="v$version"
asset="Akashic-Mobile-$tag.apk"
apk="$repo_dir/app/build/outputs/apk/release/app-release.apk"
mapping="$repo_dir/app/build/outputs/mapping/release/mapping.txt"
android_home="${ANDROID_HOME:-$HOME/Android/Sdk}"
apkanalyzer="${AKASHIC_ANDROID_APKANALYZER:-$android_home/cmdline-tools/latest/bin/apkanalyzer}"
release_commit="$(git -C "$repo_dir/../.." rev-parse HEAD)"

if [[ -n "$(git -C "$repo_dir/../.." status --porcelain)" ]]; then
    echo "发布必须从干净的 Git head 执行" >&2
    exit 1
fi

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
release_args=()
if [[ "$release_mode" == "--prerelease" ]]; then
    release_args+=(--prerelease)
fi
git -C "$repo_dir/../.." push origin "$release_commit:refs/tags/$tag"
gh release create "$tag" "$asset_path" "$mapping_path" "$checksum_path" \
    --repo "$release_repo" \
    --verify-tag \
    --title "Akashic Mobile $tag" \
    --notes "Akashic Android 客户端 $tag" \
    "${release_args[@]}"
