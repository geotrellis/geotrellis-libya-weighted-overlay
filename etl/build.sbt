name := "etl"

libraryDependencies += "com.azavea.geotrellis" %% "geotrellis-spark-etl" % Version.gtVersion

fork in Test := false
parallelExecution in Test := false
