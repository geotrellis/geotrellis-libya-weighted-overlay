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

import geotrellis.raster._
import geotrellis.raster.histogram._
import geotrellis.raster.io._
import geotrellis.raster.Tile
import geotrellis.spark._
import geotrellis.spark.etl.config._
import geotrellis.spark.etl.Etl
import geotrellis.spark.io._
import geotrellis.spark.io.file._
import geotrellis.spark.util.SparkUtils
import geotrellis.vector.ProjectedExtent

import org.apache.spark.SparkConf


object Ingest extends App {
  implicit val sc = SparkUtils.createSparkContext("Libya Weighted Overlay ETL", new SparkConf(true))

  try {
    EtlConf(args) foreach { conf =>
      val etl = Etl(conf)

      val sourceTiles =
        etl.load[ProjectedExtent, Tile]
          .mapValues { tile =>
            tile
              .mapDouble { z =>
              if(z <= 0.0 || isNoData(z)) { 0.0 }
              else { z }
            }
          }

      val (zoom, tiled) = etl.tile[ProjectedExtent, Tile, SpatialKey](sourceTiles)

      val modifiedLayer =
        tiled
          .withContext { rdd =>
            val (min, max) = rdd.minMaxDouble
            rdd
              .mapValues { tile =>
                tile.normalize(min, max, 0.0, 100.0)
              }
          }

      val s = scala.collection.mutable.Set[String]()
      etl.save[SpatialKey, Tile](LayerId(etl.input.name, zoom), modifiedLayer, { (attributeStore, layerWriter, layerId, layer) =>
        layerWriter.write(layerId, layer)

        // Save off histogram of the base layer, store in zoom 0's attributes.
        if(!s.contains(layerId.name)) {
          val histogram = layer.histogram(512)
          attributeStore.write(
            layerId.copy(zoom = 0),
            "histogram",
            histogram: Histogram[Double])
          s += layerId.name
        }
      })
    }
  } finally {
    sc.stop()
  }
}
