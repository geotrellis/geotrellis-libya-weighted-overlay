IMG  := quay.io/lossyrob/geotrellis-libya-weighted-overlay-example
TAG  := "latest"

ETL_ASSEMBLY_JAR := etl/target/scala-2.11/etl-assembly-0.1.0.jar
SERVER_ASSEMBLY_JAR := server/target/scala-2.11/libya-weighted-overlay-example-server-0.1.0.jar

clean:
	rm -rf ${SERVER_ASSEMBLY_JAR}
	rm ./build
	rm ./assemble

rwildcard=$(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))

${SERVER_ASSEMBLY_JAR}: $(call rwildcard, server, *.scala) build.sbt
	sbt "project server" assembly

${ETL_ASSEMBLY_JAR}: $(call rwildcard, etl, *.scala) build.sbt
	sbt "project etl" assembly

etl/json/input.json: etl/json/input-template.json
	@scripts/template.sh etl/json/input.json etl/json/input-template.json

etl/json/output.json: etl/json/output-template.json
	@scripts/template.sh etl/json/output.json etl/json/output-template.json

ingest: ${ETL_ASSEMBLY_JAR} etl/json/input.json etl/json/output.json
	rm -r data/catalog/ || true
	spark-submit \
		--class com.azavea.geotrellis.weighted.Ingest \
		--master local[*] \
		--driver-memory 2G \
		${ETL_ASSEMBLY_JAR} \
		--backend-profiles "file://${PWD}/etl/json/backend-profiles.json" \
		--input "file://${PWD}/etl/json/input.json" \
		--output "file://${PWD}/etl/json/output.json"

assembly: ${SERVER_ASSEMBLY_JAR}

docker-build: Dockerfile ${SERVER_ASSEMBLY_JAR}
	docker build --no-cache -t ${IMG}:${TAG} .

docker-run:
	docker run -it -p 8777:8777 ${IMG}:${TAG}

run:
	sbt "project server" run

docker-publish: docker-build
	docker push ${IMG}:${TAG}

test: docker-build
	./sbt test
	docker-compose up -d
	sleep 2 && curl localhost:7070/system/status
	docker-compose down
