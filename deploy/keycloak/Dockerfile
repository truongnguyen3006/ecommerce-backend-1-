FROM quay.io/keycloak/keycloak:26.8.0 AS build
ENV KC_DB=mysql KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build
FROM quay.io/keycloak/keycloak:26.8.0
ENV KC_DB=mysql KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=true
COPY --from=build /opt/keycloak/ /opt/keycloak/
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
