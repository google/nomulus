FROM jetty:12-jdk25
ADD --chown=jetty:jetty build/jetty-base /jetty-base
ADD --chown=jetty:jetty start.sh /
ADD --chown=jetty:jetty logging.properties /

USER root

# wget is not installed by default in :12-jdk25
RUN apt-get update && \
    apt-get install -y wget && \
    rm -rf /var/lib/apt/lists/*

# Create a directory, download and extract the Cloud Profiler agent, version locked to "cloud-profiler-java-agent_20241028_RC00.tar.gz".
RUN mkdir -p /opt/cprof && \
    wget -q -O- https://storage.googleapis.com/cloud-profiler/java/cloud-profiler-java-agent_20241028_RC00.tar.gz\
    | tar xzv -C /opt/cprof && \
    chown -R jetty:jetty /opt/cprof

USER jetty

EXPOSE 8080
# jetty:12-jdk25 has bash. We can afford the extra 200M in image size over
# the -alpine flavor.
ENTRYPOINT ["/bin/bash", "/start.sh"]
