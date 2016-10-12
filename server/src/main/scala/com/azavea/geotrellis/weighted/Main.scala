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

import akka.actor.ActorSystem
import akka.actor.Props
import akka.io.IO
import com.typesafe.config.ConfigFactory
import spray.can.Http


object Main {

  val config = ConfigFactory.load()
  val staticPath = config.getString("geotrellis.server.static-path")
  val port = config.getInt("geotrellis.port")
  val host = config.getString("geotrellis.hostname")

  def main(args: Array[String]): Unit = {

    // implicit val system = ActorSystem("weighted-demo")

    // // create and start our service actor
    // val service = system.actorOf(Props(classOf[WeightedServiceActor], staticPath, config), "weighted-service")

    // // start a new HTTP server on port 8080 with our service actor as the handler
    // IO(Http) ! Http.Bind(service, host, port)
    XXX()
  }

  def XXX(): Unit = {
    import geotrellis.raster._
    import geotrellis.spark._
    import geotrellis.spark.io._
    import org.apache.spark.{SparkConf, SparkContext}

    val conf = AvroRegistrator(
      new SparkConf()
        .setAppName("Weighted Overlay")
        .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        .set("spark.kryo.registrator", "geotrellis.spark.io.kryo.KryoRegistrator"))

    implicit val sparkContext = new SparkContext(conf)

    val (reader, tileReader, attributeStore) = initBackend(config)
    val roads = reader.read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](LayerId("roads", 12))
    val places = reader.read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](LayerId("places", 12))

    println(roads.metadata)
    println(places.metadata)
  }

}

