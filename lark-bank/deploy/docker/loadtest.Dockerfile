# Proofload driving the bank from inside the cluster, from `./gradlew :loadtest:installDist`.
FROM public.ecr.aws/docker/library/eclipse-temurin:25-jre
COPY loadtest/build/install/loadtest /opt/loadtest
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75"
ENTRYPOINT ["/opt/loadtest/bin/loadtest"]
