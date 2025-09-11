# syntax=docker/dockerfile:1

# --- Build Stage ---
FROM node:20-bullseye AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .

# Generate and apply migrations for Better Auth
# RUN npx @better-auth/cli generate --config src/auth.ts --yes && npx @better-auth/cli migrate --yes

# Build TypeScript
RUN npm run build

# Expose port
EXPOSE 5000

# Entrypoint: run the server
CMD ["/bin/sh", "-c", "npm run migration:make && npm run migration:apply && npm start"]
