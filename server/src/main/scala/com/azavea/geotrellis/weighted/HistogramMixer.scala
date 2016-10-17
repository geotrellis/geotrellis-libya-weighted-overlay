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
import geotrellis.raster.histogram.StreamingHistogram

import scala.collection.mutable

object HistogramMixer {

  /**
    * For random variables X and Y with PDFs $p_{X}$ and $p_{Y}$,
    * $p_{αX + βY} = αp_{X} \star βp_{Y}$ where $\star$ is the
    * convolution operator.
    *
    * https://en.wikipedia.org/wiki/Convolution#Discrete_convolution
    */
  private def convolve(
    alpha: Double, x: StreamingHistogram,
    beta: Double, y: StreamingHistogram
  ): StreamingHistogram = {
    val nX = x.totalCount
    val nY = y.totalCount
    val n = alpha*nX + beta*nY

    val newBuckets =
      x.buckets.flatMap({ case StreamingHistogram.Bucket(x, nx) =>
        val px = nx.toDouble / nX
        y.buckets.map({ case StreamingHistogram.Bucket(y, ny) =>
          val py = ny.toDouble / nY
          val newLabel = alpha*x + beta*y
          val newCount = (px * py * n)
          StreamingHistogram.Bucket(newLabel, newCount.toLong)
        })
      })

    val histogram = StreamingHistogram(x.maxBucketCount)
    histogram.countItems(newBuckets); histogram
  }

  val distributions = mutable.Map.empty[Int, StreamingHistogram]

  /**
    * Given $p_{X_{i}}$ and $α_{i}$, return $p_{\sum α_{i}X_{i}}$.
    */
  def apply(histograms: Array[StreamingHistogram], weights: Array[Double]): StreamingHistogram = {
    if (math.max(histograms.length, weights.length) < 2) histograms.head
    else {
      val key = histograms.toList.hashCode + weights.toList.hashCode

      distributions.synchronized {
        distributions.get(key) match {
          case None =>
            if (distributions.size >= (1<<20)) distributions.clear

            val distribution =
              histograms.zip(weights)
                .reduce({ (left, right) =>
                  val (x, alpha) = left
                  val (y, beta) = right
                  (convolve(alpha, x, beta, y), alpha + beta) })._1

            distributions += (key -> distribution)
            distribution
          case Some(distribution) =>
            distribution
        }
      }
    }
  }
}
