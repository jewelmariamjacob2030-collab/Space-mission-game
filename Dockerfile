FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY src ./src
RUN javac -d out src/MissionEvaluator.java
CMD ["java", "-cp", "out", "MissionEvaluator"]
