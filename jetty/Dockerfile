FROM jetty:12-jdk21
ADD --chown=jetty:jetty build/jetty-base /jetty-base
ADD --chown=jetty:jetty start.sh /
ADD --chown=jetty:jetty logging.properties /

USER root

# Create a directory, download and extract the Cloud Profiler agent, version locked to "cloud-profiler-java-agent_20241028_RC00.tar.gz".
RUN mkdir -p /opt/cprof && \
    wget -q -O- https://storage.googleapis.com/cloud-profiler/java/cloud-profiler-java-agent_20241028_RC00.tar.gz\
    | tar xzv -C /opt/cprof && \
    chown -R jetty:jetty /opt/cprof

USER jetty

EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/start.sh"]
