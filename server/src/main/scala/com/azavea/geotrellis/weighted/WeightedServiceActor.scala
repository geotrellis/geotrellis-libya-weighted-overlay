/*
 * Copyright (c) 2016 Azavea.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.azavea.geotrellis.weighted

import geotrellis.proj4.{LatLng, WebMercator}
import geotrellis.raster._
import geotrellis.raster.histogram.StreamingHistogram
import geotrellis.raster.mapalgebra.local._
import geotrellis.raster.render._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.AttributeStore.Fields
import geotrellis.spark.io.cassandra._
import geotrellis.vector.io.json.Implicits._
import geotrellis.vector.Polygon
import geotrellis.vector.reproject._

import akka.actor._
import com.typesafe.config.Config
import org.apache.spark.{SparkConf, SparkContext}
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.routing._

import scala.collection.JavaConversions._


class WeightedServiceActor(override val staticPath: String, config: Config) extends Actor with WeightedService {
  val conf = AvroRegistrator(
    new SparkConf()
      .setAppName("Weighted Overlay")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", "geotrellis.spark.io.kryo.KryoRegistrator")
  )

  implicit val sparkContext = new SparkContext(conf)

  override def actorRefFactory = context
  override def receive = runRoute(serviceRoute)

  val (reader, tileReader, attributeStore) = initBackend(config)

  val layerNames = attributeStore.layerIds.map(_.name).distinct

  val histograms: Map[String, StreamingHistogram] = layerNames.map({ name =>
    name -> reader
      .read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](LayerId(name, 8))
      .mapPartitions({ partition =>
        Iterator(partition
          .map({ case (_, tile) => StreamingHistogram.fromTile(tile, 1<<10) })
          .reduce(_ + _))
      }, preservesPartitioning = true)
      .reduce(_ + _) })
    .toMap

}

trait WeightedService extends HttpService {
  implicit val sparkContext: SparkContext
  implicit val executionContext = actorRefFactory.dispatcher
  val reader: FilteringLayerReader[LayerId]
  val tileReader: ValueReader[LayerId]
  val attributeStore: AttributeStore

  val staticPath: String
  val baseZoomLevel = 9

  def layerId(layer: String): LayerId =
    LayerId(layer, baseZoomLevel)

  def getMetaData(id: LayerId): TileLayerMetadata[SpatialKey] =
    attributeStore.readMetadata[TileLayerMetadata[SpatialKey]](id)

  def serviceRoute = get {
    pathPrefix("gt") {
      pathPrefix("tms")(tms) ~
        path("colors")(colors) ~
        path("breaks")(breaks)
    } ~
      pathEndOrSingleSlash {
        getFromFile(staticPath + "/index.html")
      } ~
      pathPrefix("") {
        getFromDirectory(staticPath)
      }
  }

  def colors = complete(ColorRampMap.getJson)

  def breaks =
    parameters(
      'layers,
      'weights,
      'numBreaks.as[Int]
    ) { (layersParam, weightsParam, numBreaks) =>
      import DefaultJsonProtocol._

      val layers = layersParam.split(",")
      val weights = weightsParam.split(",").map(_.toInt)

      val breaksSeq =
        layers.zip(weights)
          .map({ case (layer, weight) =>
            reader.read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](layerId(layer)).convert(ShortConstantNoDataCellType) * weight })
          .toSeq

      val breaksAdd = breaksSeq.localAdd

      val breaksArray = breaksAdd.histogramExactInt.quantileBreaks(numBreaks)

      complete(JsObject(
        "classBreaks" -> breaksArray.toJson
      ))
    }

  def tms = pathPrefix(IntNumber / IntNumber / IntNumber) { (zoom, x, y) => {
    parameters(
      'layers,
      'weights,
      'breaks,
      'bbox.?,
      'colors.as[Int] ? 4,
      'colorRamp ? "blue-to-red",
      'mask ? ""
    ) { (layersParam, weightsParam, breaksString, bbox, colors, colorRamp, maskz) =>

      import geotrellis.raster._

      val layers = layersParam.split(",")
      val weights = weightsParam.split(",").map(_.toInt)
      val breaks = breaksString.split(",").map(_.toInt)
      val key = SpatialKey(x, y)

      val maskTile =
        tileReader
          .reader[SpatialKey, Tile](LayerId("mask", zoom)).read(key)
          .convert(ShortConstantNoDataCellType)
          .mutable

      val (extSeq, tileSeq) =
        layers.zip(weights)
          .map({ case (l, weight) =>
            getMetaData(LayerId(l, zoom)).mapTransform(key) ->
            tileReader.reader[SpatialKey, Tile](LayerId(l, zoom)).read(key).convert(ShortConstantNoDataCellType) * weight })
          .toSeq
          .unzip

      val extent = extSeq.reduce(_ combine _)

      val tileAdd = tileSeq.localAdd

      val tileMap = tileAdd.map(i => if(i == 0) NODATA else i)

      val tile = tileMap.localMask(maskTile, NODATA, NODATA)

      val maskedTile =
        if (maskz.isEmpty) tile
        else {
          val poly =
            maskz
              .parseGeoJson[Polygon]
              .reproject(LatLng, WebMercator)

          tile.mask(extent, poly.geom)
        }

      val ramp = ColorRampMap.getOrElse(colorRamp, ColorRamps.BlueToRed).toColorMap(breaks)

      respondWithMediaType(MediaTypes.`image/png`) {
        complete(maskedTile.renderPng(ramp).bytes)
      }
    }
  }
  }

}
