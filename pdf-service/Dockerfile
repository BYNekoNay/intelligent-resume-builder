FROM node:20-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY pdf-service/package.json pdf-service/package-lock.json ./
RUN npm ci --omit=dev
COPY pdf-service/src ./src
USER node
EXPOSE 3001
CMD ["node", "src/server.js"]
