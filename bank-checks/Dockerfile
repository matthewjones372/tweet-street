# The check, from `sbt app/stage`: the same JRE as the bank's image.
FROM public.ecr.aws/docker/library/eclipse-temurin:25-jre
COPY target/out/jvm/scala-3.8.4/app/universal/stage /opt/checks
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8090
ENTRYPOINT ["/opt/checks/bin/app"]
