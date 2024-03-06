FROM jetty:12-jdk21
ADD --chown=jetty:jetty build/jetty-base /jetty-base
ENV JETTY_BASE=/jetty-base
WORKDIR "$JETTY_BASE"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/usr/local/jetty/start.jar"]

