# The bank, from `./gradlew :app:installDist`: the build runs on the host, where the lark snapshot is installed.
FROM public.ecr.aws/docker/library/eclipse-temurin:25-jre
RUN useradd --system --uid 10001 bank
COPY app/build/install/app /opt/bank
USER bank
EXPOSE 8080 25520
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["/opt/bank/bin/app"]
