# docker build -t softa/demo-app:1.0.0 -f ./deploy/demo-app/Dockerfile .
# Stage 1: Build app jar
FROM maven:3.9.12-eclipse-temurin-25-alpine AS build_stage

ARG APP_DIR
ARG APP_NAME
COPY . /build/
WORKDIR /build/

# Install root pom.xml first to ensure parent POM is available
RUN mvn install -N -Dgpg.skip=true

# Build target module and dependencies
RUN mvn clean install -pl ${APP_DIR}/${APP_NAME} -am -Dmaven.test.skip=true -Dgpg.skip=true

# Stage 2: Build final image with app jar
FROM eclipse-temurin:25-jre-alpine

ARG APP_DIR
ARG APP_NAME
ENV APP_NAME=${APP_NAME} \
    TZ=Asia/Shanghai

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY --from=build_stage /build/${APP_DIR}/${APP_NAME}/target/${APP_NAME}.jar /app/
COPY ./deploy/entrypoint.sh /app/entrypoint.sh

WORKDIR /app
RUN chmod +x ./entrypoint.sh

ENTRYPOINT ["./entrypoint.sh"]