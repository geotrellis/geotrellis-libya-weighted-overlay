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

package com.azavea.geotrellis

import geotrellis.spark.io._
import geotrellis.spark.io.accumulo._
import geotrellis.spark.io.cassandra._
import geotrellis.spark.io.file._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.io.hbase._
import geotrellis.spark.io.s3._
import geotrellis.spark.LayerId

import com.typesafe.config.Config
import org.apache.accumulo.core.client.security.tokens.PasswordToken
import org.apache.spark.SparkContext

import scala.collection.JavaConversions._


package object weighted {

  def initBackend(config: Config)(implicit cs: SparkContext): (FilteringLayerReader[LayerId], ValueReader[LayerId], AttributeStore)  = {
    config.getString("geotrellis.backend") match {
      case "s3" => {
        val (bucket, prefix) = config.getString("s3.bucket") -> config.getString("s3.prefix")
        val attributeStore = S3AttributeStore(bucket, prefix)

        (S3LayerReader(attributeStore), S3ValueReader(bucket, prefix), attributeStore)
      }
      case "accumulo" => {
        val instance = AccumuloInstance(
          config.getString("accumulo.instance"),
          config.getString("accumulo.zookeepers"),
          config.getString("accumulo.user"),
          new PasswordToken(config.getString("accumulo.password"))
        )

        (AccumuloLayerReader(instance), AccumuloValueReader(instance), AccumuloAttributeStore(instance))
      }
      case "hbase" => {
        val instance = HBaseInstance(
          config.getString("hbase.zookeepers").split(","),
          config.getString("accumulo.master")
        )

        (HBaseLayerReader(instance), HBaseValueReader(instance), HBaseAttributeStore(instance))
      }
      case "cassandra" => {
        val instance = BaseCassandraInstance(
          config.getStringList("cassandra.hosts").toList,
          config.getString("cassandra.user"),
          config.getString("cassandra.password"),
          config.getString("cassandra.replicationStrategy"),
          config.getInt("cassandra.replicationFactor")
        )

        (CassandraLayerReader(instance), CassandraValueReader(instance), CassandraAttributeStore(instance))
      }
      case "hadoop" => {
        val path = config.getString("hadoop.path")
        (HadoopLayerReader(path), HadoopValueReader(path), HadoopAttributeStore(path))
      }
      case "file" => {
        val path = config.getString("file.path")
        (FileLayerReader(path), FileValueReader(path), FileAttributeStore(path))
      }
      case s => throw new Exception(s"not supported backend: $s")
    }
  }

}
