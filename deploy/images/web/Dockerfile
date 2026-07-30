ARG WEB_BASE_IMAGE=databuffhub/ai-apm-web-base:17-jammy
FROM ${WEB_BASE_IMAGE}
WORKDIR /app

COPY start.sh application.yml ./
COPY skills/ ./skills/
COPY databuff/ ./databuff/
COPY *.jar ./

RUN chmod +x start.sh

ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx700m -Duser.timezone=Asia/Shanghai"

EXPOSE 27403

ENTRYPOINT ["/bin/sh", "/app/start.sh"]
