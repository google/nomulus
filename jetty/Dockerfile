FROM jetty:12-jdk21
ADD --chown=jetty:jetty build/jetty-base /jetty-base
ADD --chown=jetty:jetty start.sh /
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/start.sh"]
