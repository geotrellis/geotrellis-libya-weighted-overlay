name := "libya-weighted-overlay-server"

libraryDependencies ++= Seq(
  "org.locationtech.geotrellis" %% "geotrellis-spark" % Version.gtVersion,
  "com.typesafe.akka"     %% "akka-actor"                        % Version.akka,
  "com.typesafe.akka"     %% "akka-http-experimental"            % Version.akka,
  "com.typesafe.akka"     %% "akka-http-spray-json-experimental" % Version.akka
)


fork in Test := false
parallelExecution in Test := false

Revolver.settings
