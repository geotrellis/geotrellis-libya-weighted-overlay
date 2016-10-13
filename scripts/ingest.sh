#!/usr/bin/env bash

ulimit -n 4096
$SPARK_HOME/bin/spark-submit \
    --driver-memory=32G \
    --master='local[8]' \
    --class com.azavea.geotrellis.weighted.Ingest etl/target/scala-2.11/etl-assembly-0.jar \
    --input "file://$(pwd)/etl/json/input.json" \
    --output "file://$(pwd)/etl/json/output.json" \
    --backend-profiles "file://$(pwd)/etl/json/backend-profiles.json"
