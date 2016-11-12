package com.azavea.geotrellis.weighted

import com.typesafe.config.Config
import geotrellis.proj4.{LatLng, WebMercator}
import geotrellis.raster._
import geotrellis.raster.resample.Bilinear
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.tiling._
import geotrellis.spark.io.file._
import geotrellis.vector._

class DataModel(config: Config) {
  val (collectionReader, tileReader, attributeStore) = {
    val path = config.getString("file.path")
    val attributeStore = FileAttributeStore(path)
    (
      FileCollectionLayerReader(attributeStore),
      FileValueReader(attributeStore),
      attributeStore
    )
  }

  // A map from layer name to that layer's maximum zoom level
  val layerNamesToMaxZooms: Map[String, Int] =
    attributeStore.layerIds
      .groupBy(_.name)
      .map { case (name, layerIds) => (name, layerIds.map(_.zoom).max) }
      .toMap

  // A map from zoom level to LayoutDefinition,
  // which allows us to transform from tile key space
  // to Extent.
  val layoutMap: Map[Int, LayoutDefinition] =
    (1 to 20)
      .map { case zoom => (zoom, ZoomedLayoutScheme(WebMercator, 256).levelForZoom(zoom).layout) }
      .toMap

  def getExtent(zoom: Int, col: Int, row: Int): Extent =
    layoutMap(zoom).mapTransform(col, row)


  val breaksTileRasterExtent = layoutMap(5).createAlignedRasterExtent(VectorLayers.libya.envelope)

  // Here we read each raster layer in the catalog our at zoom 5,
  // and store integer version of the tiles off for
  // computing breaks.
  val breaksTileMap: Map[String, Tile] =
    attributeStore.layerIds
      .map(_.name)
      .distinct
      .map { name =>
        val layerId = LayerId(name, 5)
        val tile =
          collectionReader.query[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](layerId)
            .where(Intersects(VectorLayers.libya.envelope))
            .result
            .stitch
            .resample(breaksTileRasterExtent)
            .tile

        (name, tile)
      }
      .toMap

  /** Do "overzooming", where we resample lower zoom level tiles to serve out higher zoom level tiles. */
  def readTile(layer: String, zoom: Int, x: Int, y: Int): Option[Tile] =
    try {
      val z = layerNamesToMaxZooms(layer)

      if(zoom > z) {
        val layerId = LayerId(layer, z)
        val sourceMapTransform = layoutMap(z).mapTransform
        val requestZoomMapTransform = layoutMap(zoom).mapTransform

        val requestExtent = requestZoomMapTransform(x, y)
        val centerPoint = requestZoomMapTransform(x, y).center
        val SpatialKey(nx, ny) = sourceMapTransform(centerPoint)
        val sourceExtent = sourceMapTransform(nx, ny)

        val largerTile =
          tileReader.reader[SpatialKey, Tile](layerId).read(SpatialKey(nx, ny))

        Some(largerTile.resample(sourceExtent, RasterExtent(requestExtent, 256, 256), Bilinear))
      } else {
        Some(tileReader.reader[SpatialKey, Tile](LayerId(layer, zoom)).read(SpatialKey(x, y)))
      }
    } catch {
      case e: TileNotFoundError =>
        None
    }

  def createTileForVectors(layers: Seq[String], weights: Seq[Double], rasterExtent: RasterExtent): Option[Tile] = {
    val vectorLayersAndWeights =
      layers
        .zip(weights)
        .filter { case (name, _) => name.startsWith("v:") }

    if(vectorLayersAndWeights.isEmpty) {
      None
    } else {
      val tile = ArrayTile.alloc(DoubleCellType, rasterExtent.cols, rasterExtent.rows)

      for((name, weight) <- vectorLayersAndWeights) {
        VectorLayers.setToTile(name.drop(2), tile, weight, rasterExtent)
      }

      Some(tile)
    }
  }

  def getBreaks(layers: Seq[String], weights: Seq[Double], numBreaks: Int): Array[Double] = {
    val tiles =
      layers.zip(weights)
        .filter(!_._1.startsWith("v:"))
        .map { case (name, weight) =>
          breaksTileMap(name) * weight
        } ++ createTileForVectors(layers, weights, breaksTileRasterExtent).toSeq

    tiles
      .localAdd
      .convert(DoubleConstantNoDataCellType)
      .mapDouble { z => if(z == 0.0) { Double.NaN } else z }
      .mask(breaksTileRasterExtent.extent, VectorLayers.libya)
      .histogramDouble
      .quantileBreaks(numBreaks)
  }

  def suitabilityTile(layers: Seq[String], weights: Seq[Double], z: Int, x: Int, y: Int): Option[Tile] = {
    val extent = getExtent(z, x, y)

    if(!extent.intersects(VectorLayers.libya)) { None }
    else {
      val tiles =
        layers.zip(weights)
          .filter(!_._1.startsWith("v:"))
          .flatMap { case (name, weight) =>
            try {
              readTile(name, z, x, y).map(_ * weight)
            } catch {
              case e: Exception =>
                None
            }
          } ++ createTileForVectors(layers, weights, RasterExtent(extent, 256, 256)).toSeq

      if(tiles.isEmpty) { None }
      else {
        Some(
          tiles
            .localAdd
            .convert(DoubleConstantNoDataCellType)
            .mapDouble { z => if(z == 0.0) { Double.NaN } else z }
            .mask(extent, VectorLayers.libya)
        )
      }
    }
  }
}
