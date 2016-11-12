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
import geotrellis.raster.histogram._
import geotrellis.raster.io._
import geotrellis.raster.mapalgebra.local._
import geotrellis.raster.render._
import geotrellis.raster.resample.Bilinear
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.file._
import geotrellis.spark.tiling.{ZoomedLayoutScheme, LayoutDefinition}
import geotrellis.vector.io.json.Implicits._
import geotrellis.vector._
import geotrellis.vector.reproject._

import akka.actor._
import org.apache.spark.{SparkConf, SparkContext}
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.routing._

import scala.collection.JavaConversions._
import scala.concurrent.Future

class WeightedServiceActor(staticPath: String, dataModel: DataModel)
    extends Actor
    with HttpService {
  override def actorRefFactory = context
  override def receive = runRoute(serviceRoute)
  implicit val executionContext = actorRefFactory.dispatcher

  def serviceRoute =
    pathPrefix("ping") {
      complete { "pong" }
    } ~
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

  def colors = complete(ColorRampMap.getJson)

  /** http://localhost:8777/gt/breaks?layers=conflict,pipeline&weights=0.618,1.618&numBreaks=10 */
  def breaks =
    get {
      parameters(
        'layers,
        'weights,
        'numBreaks.as[Int]
      ) { (layersParam, weightsParam, numBreaks) =>
        import DefaultJsonProtocol._

        val layers = layersParam.split(",")
        val weights = weightsParam.split(",").map(_.toDouble)

        val extent = VectorLayers.libya.envelope

        val breaks =
          dataModel.getBreaks(layers, weights, numBreaks)

        complete(JsObject(
          "classBreaks" -> breaks.toJson
        ))
      }
    }

  /** http://localhost:8777/gt/tms/5/17/13?layers=pipeline&weights=5&breaks=0,146.9,220.39,293.85,367.31,440.78,514.24,11637.18,27787.32,47056.35&colorRamp=yellow-to-red-heatmap */
  def tms =
    get {
      pathPrefix(IntNumber / IntNumber / IntNumber) { (zoom, x, y) =>
        parameters(
          'layers,
          'weights,
          'colorRamp ? "blue-to-red",
          'breaks
        ) { (layersParam, weightsParam, colorRamp, classBreaksParam) =>
          val key = SpatialKey(x, y)
          val layers = layersParam.split(",")
          val weights = weightsParam.split(",").map(_.toDouble)

          val classBreaks = classBreaksParam.split(",").map(_.toDouble)
          val ramp = ColorRampMap.getOrElse(colorRamp, ColorRamps.BlueToRed)
          val colorMap =
            ramp.toColorMap(classBreaks, ColorMap.Options(fallbackColor = ramp.colors.last))

          respondWithMediaType(MediaTypes.`image/png`) {
            complete {
              Future {
                dataModel
                  .suitabilityTile(layers, weights, zoom, x, y)
                  .map(_.renderPng(colorMap).bytes)
              }
            }
          }
        }
      }
    }
}
