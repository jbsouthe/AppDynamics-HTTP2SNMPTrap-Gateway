FROM adoptopenjdk/openjdk11:latest
#version and build date for the deployment file, which should be copied to this directory for building
ARG jarFile
ARG keystorefile=testkey.jks
COPY ${jarFile} /WebRestToSNMP.jar
COPY log4j2.xml /log4j2.xml
COPY ${keystorefile} /
EXPOSE 8000
WORKDIR /
CMD ["java", "-jar", "WebRestToSNMP.jar", "/config/config.properties"]
