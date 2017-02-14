name := "etl"

libraryDependencies ++= Seq(
  "org.locationtech.geotrellis" %% "geotrellis-spark-etl" % Version.gtVersion
)

fork in Test := false
parallelExecution in Test := false
