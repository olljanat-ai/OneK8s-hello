# Two stages, the same shape as the other two applications': a build image with
# the whole toolchain in it compiles, and a runtime image with none of it
# serves. The difference is what "runtime image" can mean here — see the second
# stage.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# The POM alone first, so the dependency layer is only invalidated when a
# dependency changes rather than on every source edit — the same reason
# apps/db-hello copies its .csproj before its sources.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src/ ./src/
# The tests run in PR validation, where a failure is a red mark on the pull
# request rather than a build log nobody reads. This stage produces the jar.
RUN mvn -B -q package -DskipTests

# The JRE, not the JDK: no compiler, no Maven, and none of the ~/.m2 cache the
# stage above filled. There is no chiseled Java image the way there is for .NET
# — Temurin publishes none and neither does anyone else with the same
# guarantees — so this is an ordinary Ubuntu image with a shell and a root user
# in it, and the USER line below plus the pod's securityContext are what keep
# the application from being either. That is the same arrangement db-hello ends
# up with (SqlClient needs ICU, so it cannot use the chiseled image), and PR
# validation checks both halves of it.
FROM eclipse-temurin:21-jre-noble AS final
WORKDIR /app

# 1654 is not arbitrary: it is the UID the .NET images call "app", which the
# other two applications run as and which this chart pins in the pod's
# securityContext. One number across three images means one line in the
# platform's Pod Security notes rather than three. The account gets no
# password, no shell and a home directory only because the JVM wants somewhere
# to resolve user.home to.
RUN groupadd --gid 1654 app \
 && useradd --uid 1654 --gid 1654 --create-home --home-dir /home/app --shell /usr/sbin/nologin app

COPY --from=build /src/target/db-java.jar ./db-java.jar

USER 1654

# The same port the other two listen on, and for the same reason: binding 80
# needs a capability this user does not have. The number itself is set once, in
# src/main/resources/application.yaml; this line only declares it. Named "http"
# in the Service.
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx, so the heap follows the container
# limit the chart sets instead of being a second place to edit. ExitOnOutOfMemoryError
# because a JVM that has run out of heap should become a restarting pod rather
# than a live one that answers nothing.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/db-java.jar"]
