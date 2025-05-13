FROM public.ecr.aws/ubuntu/ubuntu:18.04_stable AS builder
RUN rm -f /etc/apt/apt.conf.d/docker-clean; echo 'Binary::apt::APT::Keep-Downloaded-Packages "true";' > /etc/apt/apt.conf.d/keep-cache
# Install required packages.
RUN \
    --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get -y update && \
    apt-get -y dist-upgrade && \
    apt-get -y install \
      build-essential \
      curl \
      software-properties-common \
      unzip \
      zip \
      && \
    curl -s https://repos.azul.com/azul-repo.key | apt-key add - && \
    apt-add-repository "deb https://repos.azul.com/zulu/deb stable main" && \
    apt-get -y install zulu21-jdk-headless && \
    add-apt-repository ppa:longsleep/golang-backports && \
    apt-get -y install golang-go
# Install bazelisk and bazel.
ARG bazel_build_version
RUN \
  --mount=type=cache,target=/root/.cache \
    go install github.com/bazelbuild/bazelisk@latest && \
    env USE_BAZEL_VERSION=${bazel_build_version} $(go env GOPATH)/bin/bazelisk version
# Add the sources.
ARG bazel_srcs_tar
ADD "${bazel_srcs_tar}" /src
ARG bazel_label
# Build bazel.
RUN \
  --mount=type=cache,target=/root/.cache \
  cd /src && \
    mkdir -p /out && \
    $(go env GOPATH)/bin/bazelisk \
      --output_user_root=/out \
      build \
      --symlink_prefix=/out/ \
      --compilation_mode=opt \
      --embed_label="${bazel_label:?}" \
      --verbose_failures \
      --stamp \
      //src:bazel
RUN cp -L /out/bin/src/bazel /out

# Discard everything except for the bazel binary.
FROM scratch
COPY --from=builder /out/bazel /
# COPY --from=builder /out/bin/bazel /bazel

# vim: filetype=dockerfile
