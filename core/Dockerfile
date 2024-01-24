FROM gcr.io/distroless/java17-debian11:debug
ADD build/libs/nomulus.jar /nomulus.jar
ENTRYPOINT ["/usr/bin/java", "-jar", "/nomulus.jar"]
