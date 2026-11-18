FROM launcher.gcr.io/google/ubuntu2404
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        openjdk-25-jre-headless \
    && rm -rf /var/lib/apt/lists/*

# Jetty Home is staged into build/jetty-home by the :jetty:unpackJettyHome
# Gradle task, at the org.eclipse.jetty:jetty-home version locked in
# jetty/gradle.lockfile.
ENV JETTY_HOME=/usr/local/jetty \
    JETTY_BASE=/jetty-base \
    TMPDIR=/tmp/jetty \
    PATH=/usr/local/jetty/bin:${PATH}

# Install the Cloud Profiler Java agent (version-locked to
# cloud-profiler-java-agent_20241028_RC00.tar.gz) under a dedicated non-root
# jetty:jetty user.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends wget; \
    rm -rf /var/lib/apt/lists/*; \
    groupadd -r jetty; \
    useradd -r -g jetty -d "${JETTY_BASE}" jetty; \
    mkdir -p "${JETTY_HOME}" "${JETTY_BASE}" "${TMPDIR}" /opt/cprof; \
    wget -q -O- \
        https://storage.googleapis.com/cloud-profiler/java/cloud-profiler-java-agent_20241028_RC00.tar.gz \
        | tar xzv -C /opt/cprof; \
    chown -R jetty:jetty "${JETTY_HOME}" "${JETTY_BASE}" "${TMPDIR}" /opt/cprof

WORKDIR /jetty-base
ADD --chown=jetty:jetty build/jetty-home /usr/local/jetty
ADD --chown=jetty:jetty build/jetty-base /jetty-base
ADD --chown=jetty:jetty start.sh /
ADD --chown=jetty:jetty logging.properties /

USER jetty

EXPOSE 8080
ENTRYPOINT ["/bin/bash", "/start.sh"]
