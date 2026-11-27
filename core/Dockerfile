FROM launcher.gcr.io/google/ubuntu2404
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        openjdk-25-jre-headless \
    && rm -rf /var/lib/apt/lists/*

ADD build/libs/nomulus.jar /nomulus.jar
# Disable C2 Base64 SIMD intrinsics as defense-in-depth for CVE-2026-114938
# (JDK-8393212).
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-jar", "/nomulus.jar"]
