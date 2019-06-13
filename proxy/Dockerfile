FROM gcr.io/distroless/java
ADD build/libs/proxy_server.jar .
ENTRYPOINT ["java", "-jar", "proxy_server.jar"]
EXPOSE 30000 30001 30002 30010 30012
