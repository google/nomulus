FROM jetty:12-jdk21
ADD --chown=jetty:jetty build/jetty-base /jetty-base
ADD --chown=jetty:jetty start.sh /
ADD --chown=jetty:jetty logging.properties /
# Create directory and download/extract Cloud Profiler agent
RUN apt-get install -y wget && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /opt/cprof && \
    wget -q -O- https://storage.googleapis.com/cloud-profiler/java/latest/profiler_java_agent.tar.gz \
    | tar xzv -C /opt/cprof
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/start.sh"]
