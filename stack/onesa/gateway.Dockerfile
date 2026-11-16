# Same build as ohs-player-reference-infrastructure@5c87af6, with both sources pinned
# by commit through named build contexts so the plugin can come from a local fork.
FROM maven:3.9.16-eclipse-temurin-21 AS gateway-build
WORKDIR /src
COPY --from=gateway . .
COPY fhir-gateway-balp-query-per-search.patch /tmp/
RUN git apply /tmp/fhir-gateway-balp-query-per-search.patch
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B package -Dspotless.apply.skip=true -Dlicense.skip=true -DskipTests
RUN cp exec/target/fhir-gateway-exec.jar /fhir-gateway.jar

FROM maven:3.9.16-eclipse-temurin-21 AS plugin-build
WORKDIR /src
COPY --from=plugin . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package -Dlicense.skip=true -DskipTests
RUN cp target/ohs-player-backend-extensions-*.jar /ohs-player-backend-extensions.jar

FROM eclipse-temurin:21.0.12_8-jre AS runtime
WORKDIR /app
COPY --from=gateway-build /fhir-gateway.jar /app/fhir-gateway.jar
COPY --from=plugin-build /ohs-player-backend-extensions.jar /app/plugins/ohs-player-backend-extensions.jar
COPY --from=gateway-build /src/resources /app/resources
EXPOSE 8080
ENTRYPOINT ["java", "-Dloader.path=/app/plugins", "-jar", "/app/fhir-gateway.jar", "--server.port=8080"]
