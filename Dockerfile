FROM java:8

EXPOSE 8777

COPY server/target/scala-2.11/libya-weighted-overlay-server-assembly-0.1.0.jar /opt/app/app.jar
COPY data/catalog /opt/data/catalog
COPY data/geojson /opt/data/geojson
COPY static /opt/static

CMD [ "java", "-jar", "/opt/app/app.jar", "com.azavea.geotrellis.weighted.Main"]
