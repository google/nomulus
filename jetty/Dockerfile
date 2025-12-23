FROM jetty:12-jdk21
ADD --chown=jetty:jetty build/jetty-base /jetty-base
ADD --chown=jetty:jetty start.sh /
ADD --chown=jetty:jetty logging.properties /
# Switch to root to install the agent
USER root

RUN apt-get update && apt-get install -y wget && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /opt/cprof && \
    wget -q -O- https://storage.googleapis.com/cloud-profiler/java/latest/profiler_java_agent.tar.gz \
    | tar xzv -C /opt/cprof

# Switch back to the jetty user
USER jetty

EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/start.sh"]
