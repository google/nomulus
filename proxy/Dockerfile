FROM eclipse-temurin:25
ADD build/libs/proxy_server.jar .
# Disable C2 Base64 SIMD intrinsics as defense-in-depth for CVE-2026-114938
# (JDK-8393212).
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-jar", "proxy_server.jar"]
EXPOSE 30000 30002 30012
