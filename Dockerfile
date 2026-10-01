# ---------- Etapa 1: build ----------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Primero solo lo necesario para resolver dependencias (mejor caché de capas)
COPY gradlew settings.gradle build.gradle lombok.config ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null

# Código fuente y empaquetado (los tests se ejecutan en el pipeline de CI)
COPY config config
COPY src src
RUN ./gradlew bootJar --no-daemon -q

# ---------- Etapa 2: runtime ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
