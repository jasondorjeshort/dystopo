# Dystopo server image (T52; T58 added the build stage and tilebuild). Build from the repository root:
#
#   docker build -f server/Dockerfile -t dystopo-server .
#   docker run -p 8080:8080 -v dystopo-cache:/data/cache -v dystopo-out:/data/out dystopo-server
#
# Stage 1 compiles the server and tilebuild distributions with the Gradle wrapper (server/Dockerfile.dockerignore
# keeps cache/, out/ and build outputs out of the context). Stage 2 is the runtime: eclipse-temurin (EPL-2.0/
# GPL-2.0 with classpath exception JRE on Ubuntu) plus the DejaVu fonts (free license) so headless AWT can
# draw the sprite sheet's marker letters, the server under /opt/dystopo and tilebuild under /opt/tilebuild.
# The server runs tilebuild as a subprocess for profile=outdoors builds (DYSTOPO_TILEBUILD_HOME); tilebuild
# carries LGPL build tooling (GeoTools), shipped unmodified as separate jars and never on the server's classpath.
# docs/SERVER.md: config keys, the token, TLS behind a reverse proxy.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src
# T92: the Gradle distribution first, in a layer of its own keyed by the wrapper files only, so a cached
# build (CI keeps BuildKit layers in the Actions cache) never downloads it again, and a fresh one retries a
# transient error from GitHub's distribution host (a 500 failed CI run 38462712750 on 2026-10-13).
COPY gradlew ./
COPY gradle/wrapper gradle/wrapper
RUN chmod +x gradlew \
    && for i in 1 2 3 4 5; do \
         ./gradlew --no-daemon --version > /dev/null && exit 0; \
         echo "Gradle distribution download failed (try $i of 5), retrying"; sleep $((i * 15)); \
       done; \
    exit 1
COPY . .
# No Android SDK in this stage, so settings.gradle.kts leaves the app module out.
RUN chmod +x gradlew \
    && ./gradlew --no-daemon --console=plain :server:installDist :tilebuild:installDist \
    && test -x server/build/install/server/bin/server \
    && ls tilebuild/build/install/tilebuild/lib/tilebuild*.jar

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin dystopo \
    && mkdir -p /data/cache /data/out \
    && chown -R dystopo /data

COPY --from=build /src/server/build/install/server /opt/dystopo
COPY --from=build /src/tilebuild/build/install/tilebuild /opt/tilebuild

# Relative paths in the config (the defaults "cache" and "out") resolve against /data. A config file
# mounted at /opt/dystopo/config/server.json is read without arguments; DYSTOPO_SERVER_TOKEN sets the
# bearer token without putting it in a file. Outdoors builds download Planetiler's aux data (about
# 1.45 GB, once) into the cache volume; DYSTOPO_TILEBUILD_HEAP is the subprocess heap (default 3g).
WORKDIR /data
USER dystopo
VOLUME ["/data/cache", "/data/out"]
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60" \
    DYSTOPO_TILEBUILD_HOME=/opt/tilebuild

# Exec form: the start script execs java, so java is PID 1 and gets SIGTERM (graceful shutdown).
ENTRYPOINT ["/opt/dystopo/bin/server"]
