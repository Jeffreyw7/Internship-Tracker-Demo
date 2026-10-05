# Build the React frontend in a dedicated Node stage.
FROM node:22-alpine AS frontend
WORKDIR /source
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY public ./public
COPY src ./src
RUN npm run build

# Compile and verify Java, bundling the frontend assets into the runnable JAR.
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /source
COPY backend ./backend
COPY deploy/postgres ./deploy/postgres
COPY --from=frontend /source/dist ./dist
RUN mvn -B -f backend/pom.xml -Pwith-ui verify && mvn -B -f backend/pom.xml dependency:copy-dependencies -DincludeScope=runtime

# Run migrations with separate database credentials before starting the application.
FROM eclipse-temurin:17-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir /app/data && chown -R 10001:10001 /app

FROM runtime AS migrate
WORKDIR /app
COPY --from=backend --chown=10001:10001 /source/backend/target/classes /app/classes
COPY --from=backend --chown=10001:10001 /source/backend/target/dependency /app/lib
USER 10001:10001
ENTRYPOINT ["java","-cp","/app/classes:/app/lib/*","com.campus.tracker.migration.MigrationMain"]

# Dedicated workers import no HTTP controllers, session store or application mutation services.
FROM runtime AS worker
WORKDIR /app
COPY --from=backend --chown=10001:10001 /source/backend/target/classes /app/classes
COPY --from=backend --chown=10001:10001 /source/backend/target/dependency /app/lib
USER 10001:10001
ENTRYPOINT ["java","-cp","/app/classes:/app/lib/*","com.campus.tracker.worker.WorkerApplication"]

FROM runtime AS app
WORKDIR /app
COPY --from=backend --chown=10001:10001 /source/backend/target/campus-tracker-2.0.0.jar /app/campus.jar
# Use an unprivileged runtime account instead of running the server as root.
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/campus.jar"]

# Same-origin static UI and private API gateway; no Java in this runtime.
FROM nginx:1.30.5-alpine AS ingress
COPY --from=frontend /source/dist /usr/share/nginx/html
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
