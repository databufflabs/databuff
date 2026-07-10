ARG JDK_IMAGE=eclipse-temurin:17-jdk-jammy
FROM ${JDK_IMAGE}
WORKDIR /app

COPY start.sh ./
COPY demo-seeder.jar ./

RUN chmod +x start.sh

ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx128m -Duser.timezone=Asia/Shanghai"

ENTRYPOINT ["/bin/sh", "/app/start.sh"]
