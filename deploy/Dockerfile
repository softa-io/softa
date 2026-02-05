# syntax=docker/dockerfile:1.7

# Stage 1: Build app jar
FROM maven:3.9.12-eclipse-temurin-25-alpine AS build_stage

ARG APP_DIR
ARG APP_NAME
WORKDIR /build

# 1. Copy source files
COPY . ./

# 2. Pre-download all dependencies
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests -Dgpg.skip=true \
    -pl ${APP_DIR}/${APP_NAME} -am \
    dependency:go-offline

# 3. Build the application
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests -Dgpg.skip=true \
    -pl ${APP_DIR}/${APP_NAME} -am \
    package

# Stage 2: Build final image with app jar
FROM eclipse-temurin:25-jre-alpine

ARG APP_DIR
ARG APP_NAME
ENV APP_NAME=${APP_NAME} TZ=UTC

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY --from=build_stage /build/${APP_DIR}/${APP_NAME}/target/${APP_NAME}.jar /app/
COPY ./deploy/entrypoint.sh /app/entrypoint.sh

WORKDIR /app
RUN chmod +x ./entrypoint.sh

ENTRYPOINT ["./entrypoint.sh"]