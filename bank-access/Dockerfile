# access-sync (lark-bank spec 0022), from `./gradlew :sync:installDist`, on the JRE every service in the estate runs.
FROM public.ecr.aws/docker/library/eclipse-temurin:25-jre
COPY sync/build/install/sync /opt/access-sync
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT [ "/opt/access-sync/bin/sync" ]
