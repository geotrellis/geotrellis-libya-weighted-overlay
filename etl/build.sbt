name := "etl"

libraryDependencies ++= Seq(
  "org.locationtech.geotrellis" %% "geotrellis-geotools"  % Version.gtVersion,
  "org.locationtech.geotrellis" %% "geotrellis-raster"    % Version.gtVersion,
  "org.locationtech.geotrellis" %% "geotrellis-shapefile" % Version.gtVersion,
  "org.locationtech.geotrellis" %% "geotrellis-spark-etl" % Version.gtVersion,
  "org.locationtech.geotrellis" %% "geotrellis-spark"     % Version.gtVersion,
  "org.locationtech.geotrellis" %% "geotrellis-vector"    % Version.gtVersion
)

fork in Test := false
parallelExecution in Test := false
