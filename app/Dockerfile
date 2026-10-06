# syntax=docker/dockerfile:1
#
# Multi-stage build for a Spring Boot API.
#   Stage 1 ("build")   : full JDK + Gradle, compiles and packages the jar.
#   Stage 2 ("runtime") : slim JRE only, runs the jar as a non-root user.
# The final image contains stage 2 only. No JDK, no Gradle, no source code.

# ---------- Stage 1: build ----------
# --platform=$BUILDPLATFORM runs Gradle on the machine's native CPU even when we
# ask for a linux/amd64 image. The jar is architecture-neutral, so only the
# runtime stage needs to match the host (Render is amd64).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 1. Copy only what Gradle needs to resolve dependencies. These files change
#    rarely, so Docker can reuse the cached dependency layer on most rebuilds.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet

# 2. Now copy the source. Editing a .java file only invalidates layers from here on.
COPY src/ src/
RUN ./gradlew bootJar --no-daemon --quiet

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as an unprivileged user. If the app is compromised, the attacker is not root.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy ONLY the jar from the build stage.
COPY --from=build --chown=spring:spring /app/build/libs/app.jar app.jar

# Documentation for humans and tools. It does NOT publish the port; `docker run -p` does.
EXPOSE 8080

# Alpine's busybox has wget, so this needs no extra packages. Shell form so
# ${PORT:-8080} expands. Docker/compose mark the container (healthy); hosts like
# Render use their own Health Check Path setting instead of this instruction.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:${PORT:-8080}/actuator/health || exit 1

# Exec form: java is PID 1 and receives SIGTERM directly, so `docker stop` is clean.
ENTRYPOINT ["java", "-jar", "app.jar"]
