FROM launcher.gcr.io/google/ubuntu2404
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        openjdk-25-jre-headless \
    && rm -rf /var/lib/apt/lists/*

# Keep JETTY_VERSION and JETTY_SHA256 aligned with org.eclipse.jetty in
# core/gradle.lockfile.
ENV JETTY_VERSION=12.1.14 \
    JETTY_SHA256=f269b7b98691a48647e54ca5bce9d1042c0eb5f12a0887879974f418f70baa23 \
    JETTY_HOME=/usr/local/jetty \
    JETTY_BASE=/jetty-base \
    TMPDIR=/tmp/jetty \
    PATH=/usr/local/jetty/bin:${PATH}

# Install Jetty Home (SHA-256 verified) and the Cloud Profiler Java agent
# (version-locked to cloud-profiler-java-agent_20241028_RC00.tar.gz) under a
# dedicated non-root jetty:jetty user.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends wget; \
    rm -rf /var/lib/apt/lists/*; \
    groupadd -r jetty; \
    useradd -r -g jetty -d "${JETTY_BASE}" jetty; \
    mkdir -p "${JETTY_HOME}" "${JETTY_BASE}" "${TMPDIR}" /opt/cprof; \
    wget -q -O /tmp/jetty-home.tar.gz \
        "https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-home/${JETTY_VERSION}/jetty-home-${JETTY_VERSION}.tar.gz"; \
    echo "${JETTY_SHA256}  /tmp/jetty-home.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/jetty-home.tar.gz -C "${JETTY_HOME}" --strip-components=1; \
    rm -f /tmp/jetty-home.tar.gz; \
    wget -q -O- \
        https://storage.googleapis.com/cloud-profiler/java/cloud-profiler-java-agent_20241028_RC00.tar.gz \
        | tar xzv -C /opt/cprof; \
    chown -R jetty:jetty "${JETTY_HOME}" "${JETTY_BASE}" "${TMPDIR}" /opt/cprof

WORKDIR /jetty-base
ADD --chown=jetty:jetty build/jetty-base /jetty-base
ADD --chown=jetty:jetty start.sh /
ADD --chown=jetty:jetty logging.properties /

USER jetty

EXPOSE 8080
ENTRYPOINT ["/bin/bash", "/start.sh"]
