FROM jetty:12-jdk21
ADD --chown=jetty:jetty build/jetty-base /jetty-base
ADD --chown=jetty:jetty start.sh /
ADD --chown=jetty:jetty logging.properties /
# 1. Switch to root to perform system-level installation
USER root

# 2. Install wget, create the directory, and extract the agent
RUN apt-get update && apt-get install -y wget && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /opt/cprof && \
    wget -q -O- https://storage.googleapis.com/cloud-profiler/java/latest/profiler_java_agent.tar.gz \
    | tar xzv -C /opt/cprof && \
    # 3. Change ownership so the jetty user can access everything
    chown -R jetty:jetty /opt/cprof

# 4. Switch back to the non-root user for security
USER jetty

EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/start.sh"]
