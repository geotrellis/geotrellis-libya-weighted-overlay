## Weighted overlay application for predicting Daesh presence in Libya

### Ingest
To ingest:
```sh
> make ingest
```

Ingest process takes rasters in `data/rasters` folder, reprojects them to `EPSG:3857` and builds a raster pyramid for each image.
These pyramids are used by the tile server to provide the base tiles for weighted overlay endpoint.

The ingest process is configured by `etc/json/input-template.json` and `etl/json/output-template.json`.
The templates allow to generate URIs relative to the project checkout directory.

The ingest is based on GeoTrellis ETL utility. Full documetnation can be found here.
Included sample configuration outputs the pyramid to local file system. However other options, such as S3, are supported.
For raster sets too large to be processed on a computer this process may be run on a cluster.

## Tile Server

To run the server locally, through SBT:

```sh
> make run
```

### Docker images

To build and run the Docker image suitable for deployment:

```sh
> make docker-build
> make docker-run
```

The docker image name and path can be customized through `${IMG}` `${TAG}` variables in the `Makefile`

Because used layers are small in size and are at low resolution the catalogs can be fully included in the docker image.
If larger layers were to be used they would need to be stored on remote service such as Amazon S3.