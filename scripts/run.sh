#!/usr/bin/env bash

$SPARK_HOME/bin/spark-submit \
    --class com.azavea.geotrellis.weighted.Server server/target/scala-2.11/server-assembly-0.jar
