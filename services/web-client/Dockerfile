# Build stage
FROM node:20-alpine AS build

WORKDIR /app

# npm ci needs both files and uses the lockfile for reproducible installs.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# Runtime stage
FROM nginx:1.27-alpine

# Vite builds to /app/dist.
COPY --from=build /app/dist /usr/share/nginx/html

# SPA routing: unknown paths must fall back to index.html rather than 404.
RUN printf 'server {\n\
    listen 80;\n\
    root /usr/share/nginx/html;\n\
    index index.html;\n\
    location / {\n\
        try_files $uri $uri/ /index.html;\n\
    }\n\
}\n' > /etc/nginx/conf.d/default.conf

EXPOSE 80

# Note: the API base URL is baked in at build time by Vite, so it cannot be
# changed by a runtime environment variable. Pass it as a build argument when
# the API host differs per environment.
