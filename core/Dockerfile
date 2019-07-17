FROM gcr.io/distroless/java:debug
ADD build/libs/nomulus.jar /nomulus.jar
ENTRYPOINT ["/usr/bin/java", "-jar", "/nomulus.jar"]
