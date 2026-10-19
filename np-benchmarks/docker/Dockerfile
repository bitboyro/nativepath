# syntax=docker/dockerfile:1.7
#
# The benchmark image. Build from the repository root:
#
#   docker build -f np-benchmarks/docker/Dockerfile -t np-bench:local .
#
# Stages:
#   builder   builds the release bundle (scripts/package-release.sh) on Rocky Linux 8, so libnativepath.so
#             needs nothing newer than glibc 2.28 and runs on RHEL-family and Ubuntu hosts alike
#   bundle    only the bundle tarball (docker build --target bundle -o dist . writes it to ./dist)
#   runtime   the bundle unpacked into /opt/np with a JDK and the few tools it uses; ENTRYPOINT is np-bench
#
# The runtime is the very bundle a release ships, so a passing `check` here also tests the bundle.
ARG BASE_IMAGE=rockylinux:8

FROM ${BASE_IMAGE} AS builder
ARG JDK_MAJOR=23
ARG RUST_VERSION=1.99.1
ARG MAVEN_VERSION=3.9.13
ARG NP_VERSION=0.1.0-dev
RUN dnf -y install gcc gcc-c++ make pkgconf-pkg-config curl tar gzip which git python39 findutils \
    && alternatives --set python3 /usr/bin/python3.9 && dnf clean all
RUN set -eu; arch=$(uname -m); case "$arch" in x86_64) a=x64 ;; aarch64) a=aarch64 ;; *) echo "unsupported $arch" >&2; exit 1 ;; esac; \
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/${JDK_MAJOR}/ga/linux/${a}/jdk/hotspot/normal/eclipse" -o /tmp/jdk.tgz; \
    mkdir /opt/jdk && tar xzf /tmp/jdk.tgz -C /opt/jdk --strip-components=1 && rm /tmp/jdk.tgz
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/mvn.tgz \
    && mkdir /opt/maven && tar xzf /tmp/mvn.tgz -C /opt/maven --strip-components=1 && rm /tmp/mvn.tgz
RUN curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain "${RUST_VERSION}"
ENV JAVA_HOME=/opt/jdk PATH=/root/.cargo/bin:/opt/maven/bin:/opt/jdk/bin:$PATH
WORKDIR /src
COPY . /src
# The caches make a rebuild after a small change cheap; the bundle itself is copied out of the cached
# target/ in the same step, so nothing the next stage needs lives only in a cache.
RUN --mount=type=cache,target=/root/.cargo/registry \
    --mount=type=cache,target=/root/.cargo/git \
    --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/src/target \
    scripts/package-release.sh "${NP_VERSION}" /dist

FROM scratch AS bundle
COPY --from=builder /dist/ /

FROM ${BASE_IMAGE} AS runtime
RUN dnf -y install python39 openssl rsync util-linux procps-ng perf curl iproute which tar gzip hostname openssh-clients \
    && alternatives --set python3 /usr/bin/python3.9 && dnf clean all
COPY --from=builder /opt/jdk /opt/jdk
COPY --from=builder /dist/np-*.tar.gz /tmp/bundle.tgz
RUN mkdir -p /opt/np /work && tar xzf /tmp/bundle.tgz --strip-components=1 -C /opt/np && rm /tmp/bundle.tgz \
    && chmod 1777 /opt/np /opt/np/target /work
# The scripts write certificates, class path files and server logs next to the bundle, and as whichever
# user runs the container: /opt/np, /opt/np/target (the memory scans' helper writes its file there) and
# /work are world-writable (sticky).
ENV JAVA_HOME=/opt/jdk PATH=/opt/np:/opt/jdk/bin:$PATH HOME=/tmp NP_IN_CONTAINER=1
LABEL org.opencontainers.image.title="np-bench" org.opencontainers.image.source="https://github.com/bitboyro/nativepath"
WORKDIR /work
ENTRYPOINT ["/opt/np/np-bench"]
CMD ["help"]
