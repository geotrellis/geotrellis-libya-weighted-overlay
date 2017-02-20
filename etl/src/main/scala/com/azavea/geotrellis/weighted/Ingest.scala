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

import geotrellis.geotools._
import geotrellis.raster._
import geotrellis.raster.histogram._
import geotrellis.raster.io._
import geotrellis.raster.Tile
import geotrellis.shapefile._
import geotrellis.spark._
import geotrellis.spark.costdistance._
import geotrellis.spark.etl.config._
import geotrellis.spark.etl.Etl
import geotrellis.spark.io._
import geotrellis.spark.io.file._
import geotrellis.spark.pyramid.Pyramid
import geotrellis.spark.tiling.ZoomedLayoutScheme
import geotrellis.spark.util.SparkUtils
import geotrellis.vector._
import geotrellis.proj4.CRS

import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD


object Ingest extends App {
  implicit val sc = SparkUtils.createSparkContext("Libya Weighted Overlay ETL", new SparkConf(true))

  val costDistance: Boolean = args.contains("--costdistance")
  val args2 = {
    val index = args.indexOf("--costdistance")
    if (index >= 0) args.take(index) ++ args.drop(index+2)
    else args
  }

  try {
    EtlConf(args2) foreach { conf =>
      val etl = Etl(conf)

      // Load the source tiles,
      // but modify so that negative values
      // or NoData values are set to 0
      val sourceTiles =
        etl.load[ProjectedExtent, Tile]
          .mapValues { tile =>
            tile
              .delayedConversion(ShortConstantNoDataCellType)
              .map { z =>
                if(2 <= z || z < 254) { z }
                else { NODATA }
              }
          }

      val (zoom, tiled) =
        etl.tile[ProjectedExtent, Tile, SpatialKey](sourceTiles)

      val modifiedLayer: TileLayerRDD[SpatialKey] =
        tiled
          // .convert()
          // .withContext { rdd: RDD[(SpatialKey, Tile)] =>
          //   val (min, max) = rdd.minMaxDouble
          //   rdd
          //     .mapValues { tile =>
          //       tile.normalize(min, max, 0.0, 100.0)
          //     }
          // }

      val s = scala.collection.mutable.Set[String]()

      etl.save[SpatialKey, Tile](LayerId(etl.input.name, zoom), modifiedLayer,
        { (attributeStore, layerWriter, layerId, layer: TileLayerRDD[SpatialKey]) =>

          if (!costDistance) {
            layerWriter.write(layerId, layer)

            // Save off histogram of the base layer, store in zoom 0's attributes.
            if(!s.contains(layerId.name)) {
              val histogram = layer.histogram()
              attributeStore.write(
                layerId.copy(zoom = 0),
                "histogram",
                histogram
              )
              s += layerId.name
            }
          }
          else if (costDistance && s.isEmpty) {
            require(!etl.output.pyramid)
            s += layerId.name

            val Array(costLayer, shapeFile, maxCost) =
              args(args.indexOf("--costdistance") + 1).split(",")
            val points: List[Point] =
              ShapeFileReader
                .readSimpleFeatures(shapeFile)
                .map({ sf => sf.toGeometry[Point] })
            val cost = layer.costdistance(points, maxCost.toDouble)

            // Write histogram for cost layer
            val histogram = cost.histogram()
            attributeStore.write(LayerId(costLayer, 0), "histogram", histogram)

            // Write cost pyramid
            val crs = CRS.fromName(etl.output.crs.getOrElse(etl.input.crs.getOrElse(throw new Exception)))
            val tileSize = etl.output.tileSize
            val layoutScheme = ZoomedLayoutScheme(crs, tileSize)
            Pyramid.upLevels(cost, layoutScheme, layerId.zoom, 1)({ (rdd, zoom) =>
              val id = LayerId(costLayer, zoom)
              layerWriter.write(id, rdd)
            })
          }
        }
      )
    }
  } finally {
    sc.stop()
  }
}
