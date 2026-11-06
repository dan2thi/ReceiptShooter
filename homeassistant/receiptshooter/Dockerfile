# Both public images are pinned by digest. The jq image contains a static binary.
FROM ghcr.io/jqlang/jq:1.8.2@sha256:b9c68867e5766576263a222e91db3de422d802069c7af70440e667a95344e486 AS jq
FROM eclipse-temurin:21.0.13_9-jre-alpine@sha256:62bf9bb9847846f8087545330db86e84dbdf5a13e7fe49a29f31d329091203c6

COPY --from=jq /jq /usr/local/bin/jq
WORKDIR /opt/receiptshooter
COPY run.sh receiptshooter.jar ./
RUN chmod 0555 /opt/receiptshooter/run.sh /usr/local/bin/jq
EXPOSE 8080

# Keep Temurin's certificate entrypoint; it execs this script. Supervisor's
# default init forwards signals and reaps children; the script then execs Java.
CMD ["/opt/receiptshooter/run.sh"]
