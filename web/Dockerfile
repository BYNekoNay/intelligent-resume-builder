FROM node:20-bookworm-slim AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . ./
ARG VITE_API_BASE_URL=/
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
