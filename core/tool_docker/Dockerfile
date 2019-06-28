FROM marketplace.gcr.io/google/debian9

RUN apt-get update -y && apt-get upgrade && apt-get install -y openjdk-8-jdk-headless:amd64
ADD nomulus.jar /nomulus.jar
ENTRYPOINT ["/usr/bin/java", "-jar", "/nomulus.jar"]
