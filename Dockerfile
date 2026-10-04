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
COPY --from=frontend /source/dist ./dist
RUN mvn -B -f backend/pom.xml -Pwith-ui verify

# Run only the packaged application in the final Java runtime image.
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir /app/data && chown -R 10001:10001 /app
COPY --from=backend --chown=10001:10001 /source/backend/target/campus-tracker-2.0.0.jar /app/campus.jar
# Use an unprivileged runtime account instead of running the server as root.
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/campus.jar"]
