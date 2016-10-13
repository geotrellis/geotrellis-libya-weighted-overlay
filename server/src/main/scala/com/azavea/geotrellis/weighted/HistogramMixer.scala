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


object HistogramMixer {

  // XXX memoize
  def apply(histograms: Seq[StreamingHistogram], weights: Seq[Double]): StreamingHistogram = {
    val histogram = StreamingHistogram(histograms.head.maxBucketCount)
    val buckets =
      histograms
        .map(_.buckets).zip(weights)
        .flatMap({ case (buckets, weight) =>
          buckets.map({ case StreamingHistogram.Bucket(label, count) =>
            StreamingHistogram.Bucket(label, (count*weight).toInt)
          })
        })

    histogram.countItems(buckets)
    histogram
  }

}
