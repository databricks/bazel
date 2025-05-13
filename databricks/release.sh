#!/bin/bash
set -euo pipefail

source_dir="${BASH_SOURCE[0]%/*}"

bazel_version=$(sed -Ene 's/## Release (.*) \(.*\)/\1/p;t' -e 'q' CHANGELOG.md)
revision_id=$(git rev-parse --short=12 HEAD)
revision_date=$(git show -s --format=%cs HEAD)

: ${bazel_version:?failed to determine Bazel version}
: ${revision_id:?failed to determine commit id}
: ${revision_date:?failed to determine commit date}

echo "Bazel version: ${bazel_version}"
echo "Revision: ${revision_id}"

label="${bazel_version}-databricks-${revision_id}"

echo "Label: ${label}"

build_args=(
  --compilation_mode=opt
  --embed_label="${label}"
  --stamp
)

platform_name="$(uname -s | tr '[:upper:]' '[:lower:]')_$(uname -m)"
output_dir="output/${platform_name}"
output_user_root="${output_dir}/root"

build_cmd=(
  bazelisk
  --output_user_root="${output_user_root}"
  build
  --symlink_prefix="${output_dir}/bazel-"
  --verbose_failures
  "${build_args[@]}"
  //src:bazel
  //:bazel-srcs
)

echo
echo "Running build command: ${build_cmd[*]}"
echo
"${build_cmd[@]}"

bazel_bin_dir=$(bazelisk --output_user_root="${output_user_root}" info "${build_args[@]}" bazel-bin)
bazel_binary="${bazel_bin_dir}/src/bazel"
bazel_srcs_tar="${bazel_bin_dir}/bazel-srcs.tar"

cp -f "${bazel_binary}" "${output_dir}"

echo
echo "Successfully built bazel:"
ls -lh "${bazel_binary}" "${bazel_srcs_tar}"

if ! command -v finch >/dev/null; then
  echo "finch is not installed, will not build binaries for other platforms"
  exit
fi

if [[ "$(finch vm status)" != Running ]]; then
  echo 'finch is not running, please start with "finch vm start" or "finch vm init"'
  exit
fi

bazel_build_version=$(<.bazelversion)

echo
echo "Building Bazel with finch ..."

finch_build_cmd=(
  finch
    build
    --platform amd64,arm64
    --build-arg "bazel_build_version=${bazel_build_version}"
    --build-arg "bazel_label=${label}"
    --build-arg "bazel_srcs_tar=${bazel_srcs_tar#${PWD}/}"
    --output output
    --file databricks/release.Containerfile
    .
)

echo
echo "Running build_command: ${finch_build_cmd[*]}"
${finch_build_cmd[@]}
