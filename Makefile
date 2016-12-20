IMG  := quay.io/lossyrob/geotrellis-libya-weighted-overlay-example
TAG  := "ross"

ETL_ASSEMBLY_JAR := etl/target/scala-2.11/etl-assembly-0.1.0.jar
SERVER_ASSEMBLY_JAR := server/target/scala-2.11/libya-weighted-overlay-example-server-0.1.0.jar

clean:
	rm -rf ${SERVER_ASSEMBLY_JAR}
	rm ./build
	rm ./assemble

rwildcard=$(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))

${SERVER_ASSEMBLY_JAR}: $(call rwildcard, server, *.scala) build.sbt
	sbt "project server" assembly

ingest:
	rm -r data/catalog/ || true
	sbt "project etl" assembly
	spark-submit \
		--class com.azavea.geotrellis.weighted.Ingest \
		--master local[*] \
		--driver-memory 2G \
		etl/target/scala-2.11/etl-assembly-0.1.0.jar \
		--backend-profiles "file://${PWD}/etl/json/backend-profiles.json" \
		--input "file://${PWD}/etl/json/input-ross.json" \
		--output "file://${PWD}/etl/json/output.json"

assembly: ${SERVER_ASSEMBLY_JAR}

build: Dockerfile assembly
	docker build -t ${IMG}:${TAG} .

run:
	docker run -it -p 8777:8777 ${IMG}:${TAG}

publish: build
	docker push ${IMG}:${TAG}

test: build
	./sbt test
	docker-compose up -d
	sleep 2 && curl localhost:7070/system/status
	docker-compose down
