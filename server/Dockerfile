# Dystopo server image (T52). The build context is the installed distribution, which carries this file:
#
#   ./gradlew :server:installDist
#   docker build -t dystopo-server server/build/install/server
#   docker run -p 8080:8080 -v dystopo-cache:/data/cache -v dystopo-out:/data/out dystopo-server
#
# eclipse-temurin (EPL-2.0/GPL-2.0 with classpath exception JRE on Ubuntu) is the base; the image adds
# the DejaVu fonts (free license) so headless AWT can draw the sprite sheet's marker letters.
# docs/SERVER.md: config keys, the token, TLS behind a reverse proxy.
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin dystopo \
    && mkdir -p /data/cache /data/out \
    && chown -R dystopo /data

COPY . /opt/dystopo

# Relative paths in the config (the defaults "cache" and "out") resolve against /data. A config file
# mounted at /opt/dystopo/config/server.json is read without arguments; DYSTOPO_SERVER_TOKEN sets the
# bearer token without putting it in a file.
WORKDIR /data
USER dystopo
VOLUME ["/data/cache", "/data/out"]
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Exec form: the start script execs java, so java is PID 1 and gets SIGTERM (graceful shutdown).
ENTRYPOINT ["/opt/dystopo/bin/server"]
