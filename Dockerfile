# Етап 1: Збірка (Build)
# Використовуємо образ із Maven та Java 21
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Копіюємо файл налаштувань (щоб закешувати залежності)
COPY pom.xml .
# Копіюємо вихідний код
COPY src ./src

# Збираємо проект.
# Важливо: додаємо -DskipTests, бо під час білда база даних ще недоступна
RUN mvn clean package -DskipTests

# Етап 2: Запуск (Run)
# Використовуємо легкий образ тільки з JRE (щоб контейнер займав менше місця)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Копіюємо готовий jar файл з першого етапу (build)
COPY --from=build /app/target/manager-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]