# Approvals, from `./gradlew :app:installDist`: the build runs on the host, where the Lark and Pelican snapshots are,
# as the bank's image is built. The policies ship beside it; a cluster may mount a ConfigMap over them.
FROM public.ecr.aws/docker/library/eclipse-temurin:25-jre
RUN useradd --system --uid 10001 approvals
COPY app/build/install/app /opt/approvals
COPY policies /opt/approvals/policies
USER approvals
EXPOSE 8070 25530
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError" \
    POLICIES=/opt/approvals/policies/policies.yaml
ENTRYPOINT ["/opt/approvals/bin/app"]
