.PHONY: clean cleaner cleanest ingest accled airstrikes allies refineries ingest-rest
IMG  := quay.io/lossyrob/geotrellis-libya-weighted-overlay-example
TAG  := "latest"

ETL_ASSEMBLY_JAR := etl/target/scala-2.11/etl-assembly-0.1.0.jar
SERVER_ASSEMBLY_JAR := server/target/scala-2.11/libya-weighted-overlay-example-server-0.1.0.jar

rwildcard=$(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))


${SERVER_ASSEMBLY_JAR}: $(call rwildcard, server, *.scala) build.sbt
	sbt "project server" assembly

${ETL_ASSEMBLY_JAR}: $(call rwildcard, etl, *.scala) build.sbt
	sbt "project etl" assembly

%.json: %.template
	@scripts/template.sh $@ $<

accled airstrikes allies refineries: ${ETL_ASSEMBLY_JAR} etl/json/friction-input.json etl/json/friction-output.json etl/json/backend-profiles.json
	rm -rf ${PWD}/data/catalog/$@
	rm -f ${PWD}/data/catalog/attributes/$@*.json
	spark-submit \
		--class com.azavea.geotrellis.weighted.Ingest \
		--master local[*] \
		--driver-memory 12G \
		${ETL_ASSEMBLY_JAR} \
		--backend-profiles "file://${PWD}/etl/json/backend-profiles.json" \
		--input "file://${PWD}/etl/json/friction-input.json" \
		--output "file://${PWD}/etl/json/friction-output.json" \
		--costdistance "$@,${PWD}/data/shapefiles/$@/$@.shp,200000"

ingest-rest: ${ETL_ASSEMBLY_JAR} etl/json/input.json etl/json/output.json etl/json/backend-profiles.json
	rm -rf ${PWD}/data/catalog/conflict   ${PWD}/data/catalog/attributes/conflict*.json
	rm -rf ${PWD}/data/catalog/pipeline   ${PWD}/data/catalog/attributes/pipeline*.json
	rm -rf ${PWD}/data/catalog/population ${PWD}/data/catalog/attributes/population*.json
	rm -rf ${PWD}/data/catalog/weapons    ${PWD}/data/catalog/attributes/weapons*.json
	rm -rf ${PWD}/data/catalog/people     ${PWD}/data/catalog/attributes/people*.json
	spark-submit \
		--class com.azavea.geotrellis.weighted.Ingest \
		--master local[*] \
		--driver-memory 2G \
		${ETL_ASSEMBLY_JAR} \
		--backend-profiles "file://${PWD}/etl/json/backend-profiles.json" \
		--input "file://${PWD}/etl/json/input.json" \
		--output "file://${PWD}/etl/json/output.json"

ingest: accled airstrikes allies refineries ingest-rest

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


clean:
	rm -f ${ETL_ASSEMBLY_JAR} ${SERVER_ASSEMBLY_JAR}

cleaner: clean
	sbt "project etl" clean
	sbt "project server" clean

cleanest: cleaner
	rm -rf catalog
	rm -f etl/json/input.json
	rm -f etl/json/output.json
