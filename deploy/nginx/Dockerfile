FROM nginx:1.30.5-alpine@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94
COPY deploy/nginx/nginx.conf.template /etc/nginx/nginx.conf.template
COPY --chmod=755 deploy/nginx/start.sh /start.sh
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/start.sh"]
