# Jenkins LTS + JDK 21 + Docker CLI (DinD para docker build en el pipeline)
FROM jenkins/jenkins:lts-jdk21

USER root

# Docker CLI para el stage "Docker Build" del Jenkinsfile (montar /var/run/docker.sock)
RUN apt-get update \
    && apt-get install -y --no-install-recommends docker.io \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

USER jenkins