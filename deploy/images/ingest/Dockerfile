ARG JDK_IMAGE=eclipse-temurin:17-jdk-jammy
FROM ${JDK_IMAGE}
WORKDIR /app

COPY start.sh application.yml ./
COPY *.jar ./

RUN chmod +x start.sh

ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-Xms512m -Xmx1536m -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai"

EXPOSE 4317 4318 8081 18112

ENTRYPOINT ["/bin/sh", "/app/start.sh"]
