# server/Dockerfile — rustify-server for a NAS (docs serie-4.x/28 §6).
#
# Built by the release workflow from binaries compiled outside it, one per architecture under
# dist/<TARGETARCH>/ (amd64, arm64, arm): QEMU compiling Rust is many times slower than building on
# the matching runner. The base is the runners' own Ubuntu, so the binaries' glibc is there.
#
#   docker run -d -p 8484:8484 -v rustify:/data ghcr.io/pablosoage/rustify-server
#   then open it from the home network: the first visit creates the account (name and password)

FROM ubuntu:24.04
ARG TARGETARCH
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /data rustify \
 && mkdir -p /data /web \
 && chown rustify /data
COPY dist/${TARGETARCH}/rustify-server /usr/local/bin/rustify-server
COPY dist/web/ /web/
# 0.0.0.0 inside the container: what reaches it is decided by the port mapping (docs 28 §5).
ENV RUSTIFY_BIND=0.0.0.0:8484 \
    RUSTIFY_DATA_DIR=/data \
    RUSTIFY_WEB_ROOT=/web
VOLUME ["/data"]
EXPOSE 8484
USER rustify
ENTRYPOINT ["/usr/local/bin/rustify-server"]
