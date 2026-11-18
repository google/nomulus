FROM eclipse-temurin:25
ADD build/libs/nomulus.jar /nomulus.jar
# Disable C2 Base64 SIMD intrinsics as defense-in-depth for CVE-2026-114938
# (JDK-8393212).
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-jar", "/nomulus.jar"]
