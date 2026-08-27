# --- Сборка ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Отдельный слой с зависимостями, чтобы не перекачивать их при каждом изменении кода
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package

# --- Запуск ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /build/target/mcp-yandex-*.jar /app/mcp-yandex.jar

# Вложения сохраняются сюда; примонтируйте каталог хоста через -v
ENV YANDEX_MCP_ATTACHMENTS_DIR=/attachments
# Часовой пояс для отображения дат и трактовки локального времени встреч
ENV TZ=Europe/Moscow
RUN apk add --no-cache tzdata && mkdir -p /attachments

ENTRYPOINT ["java", "-jar", "/app/mcp-yandex.jar"]
