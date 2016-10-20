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


object TileMixer {

  def apply(
    tiles: Seq[Tile],
    weights: Seq[Double],
    transparent: Set[Double]
  ): DoubleArrayTile = {
    val cols = tiles.head.cols
    val rows = tiles.head.rows

    val doubleArray =
      (if (math.max(tiles.length, weights.length) < 2) {
        tiles.head.toArrayDouble
      } else {
        tiles
          .map({ tile => tile.toArray }).zip(weights)
          .map({ case (array, weight) =>
            array.map({ z =>
              if (!isData(z)) Double.NaN
              else z*weight })
          }) // one array per source tile
          .reduce({ (left: Array[Double], right: Array[Double]) =>
            left.zip(right).map({ case (a, b) => a + b })
          }) // the sum of the arrays
      }).map({ z =>
          if (transparent.contains(z)) Double.NaN
          else z
      }) // mask out values which should be transparent

    DoubleArrayTile(doubleArray, cols, rows, DoubleConstantNoDataCellType)
  }

}
