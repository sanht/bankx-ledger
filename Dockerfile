# ETAPA 1: BUILD - Compilar el código
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Copiar archivos que Gradle necesita
COPY build.gradle settings.gradle gradlew lombok.config ./
COPY gradle/ ./gradle/
COPY src/ ./src/

# Compilar con Gradle
RUN ./gradlew bootJar --no-daemon

# ETAPA 2: RUNTIME - Solo ejecutar la app
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copiar el jar desde la etapa build
COPY --from=build /workspace/build/libs/*.jar app.jar

# Puerto en el que escucha la app
EXPOSE 8080
 
# Comando de arranque
ENTRYPOINT ["java", "-jar", "app.jar"]
