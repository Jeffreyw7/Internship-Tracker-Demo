FROM node:22-alpine AS frontend
WORKDIR /source
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY public ./public
COPY src ./src
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /source
COPY backend ./backend
COPY deploy/postgres ./deploy/postgres
COPY --from=frontend /source/dist ./dist
RUN mvn -B -f backend/pom.xml -Pwith-ui verify && mvn -B -f backend/pom.xml dependency:copy-dependencies -DincludeScope=runtime

FROM eclipse-temurin:17-jre AS migrate
WORKDIR /app
COPY --from=backend --chown=10001:10001 /source/backend/target/classes /app/classes
COPY --from=backend --chown=10001:10001 /source/backend/target/dependency /app/lib
USER 10001:10001
ENTRYPOINT ["java","-cp","/app/classes:/app/lib/*","com.campus.tracker.migration.MigrationMain"]

FROM eclipse-temurin:17-jre AS app
WORKDIR /app
RUN mkdir /app/data && chown -R 10001:10001 /app
COPY --from=backend --chown=10001:10001 /source/backend/target/campus-tracker-2.0.0.jar /app/campus.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/campus.jar"]
