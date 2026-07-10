ARG JDK_IMAGE=eclipse-temurin:17-jdk-jammy
FROM ${JDK_IMAGE}
WORKDIR /app

COPY start.sh application.yml ./
COPY skills/ ./skills/
COPY *.jar ./

RUN printf 'Acquire::Retries "5";\nAcquire::http::Timeout "120";\n' > /etc/apt/apt.conf.d/80-retries \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openssh-client \
        sshpass \
        procps \
        iproute2 \
        nmap \
    && rm -rf /var/lib/apt/lists/* \
    && printf 'Host *\n  GSSAPIAuthentication no\n  PreferredAuthentications publickey,password\n  ConnectTimeout 10\n' \
        > /etc/ssh/ssh_config.d/99-databuff.conf \
    && chmod +x start.sh \
    && mkdir -p ./data/skills ./data/ai-workspaces

ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx700m -Duser.timezone=Asia/Shanghai"

EXPOSE 27403

ENTRYPOINT ["/bin/sh", "/app/start.sh"]
