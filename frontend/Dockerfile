# Static Angular build served by unprivileged nginx (ADR-019). One image for every environment:
# the API is always reached same-origin under /api, and only the upstream URL nginx proxies to
# is set at container start (API_GATEWAY_URL).
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build

FROM nginxinc/nginx-unprivileged:1.31-alpine
# The image's entrypoint runs envsubst over /etc/nginx/templates/*.template into conf.d,
# replacing its default site. Only API_GATEWAY_URL is substituted, so nginx's own $variables stay.
ENV NGINX_ENVSUBST_FILTER=^API_GATEWAY_URL$
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 8080
