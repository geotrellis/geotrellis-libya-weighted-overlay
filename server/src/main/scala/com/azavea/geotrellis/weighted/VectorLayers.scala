package com.azavea.geotrellis.weighted

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.vector._
import geotrellis.vector.io._
import geotrellis.vector.io.json._

import spray.json._
import DefaultJsonProtocol._

case class AirStrike(strikes: Int)

object AirStrike {
  implicit object AirStrikeJsonReader extends RootJsonReader[AirStrike] {
    def read(json: JsValue) =
      json.asJsObject.getFields("Number of Strikes") match {
        case Seq(JsString(strikes)) =>
          val s = strikes.toString.split(" or ")
          AirStrike(s(s.length - 1).toInt)
        case v =>
          throw new DeserializationException(s"AirStrike Expected, got $v")
      }
  }
}

// Points of population (popNum)
case class Population(population: Int, popDifSirt: Double, popDifSirtNormalized: Double)

object Population {
  implicit object PopulationJsonReader extends RootJsonReader[Population] {
    def read(json: JsValue) =
      json.asJsObject.getFields("popnum", "PopDifSirt", "PopSirte") match {
        case Seq(JsNumber(population), JsNumber(popDifSirt), JsNumber(popDifSirtNormalized)) =>
          Population(population.toInt, popDifSirt.toDouble, popDifSirt.toDouble)
        case _ =>
          throw new DeserializationException("Population Expected")
      }
  }
}

object VectorLayers {
  val AirStrikeName = """airstrikes:([\d]+)""".r
  val PopulationName = """population:([\d]+)""".r
  val WeaponRouteName = """weaponRoute:([\d]+)""".r
  val PeopleRouteName = """peopleRoute:([\d]+)""".r
  val DrugRouteName = """drugRoute:([\d]+)""".r
  val RefineriesName = """refineries:([\d]+)""".r
  val IsAlliesName = """isAllies:([\d]+)""".r

  def bufferTile(
    tile: MutableArrayTile,
    weight: Double,
    d: Int,
    buffered: MultiPolygon,
    rasterExtent: RasterExtent
  ): Tile = {
    rasterExtent.foreach(buffered) { (col, row) =>
      val z = tile.getDouble(col, row) + 50.0 * weight
      tile.setDouble(col, row, z)
    }

    tile
  }

  val bufferedCache =
    new java.util.concurrent.ConcurrentHashMap[String, (Int, MultiPolygon)]()

  def getBuffered(name: String, bufferSize: Int)(create: => MultiPolygon): MultiPolygon = {
    if(bufferedCache.containsKey(name)) {
      val (d, mp) = bufferedCache.get(name)
      if(d == bufferSize) {
        mp
      } else {
        val newMp = create
        bufferedCache.put(name, (bufferSize, mp))
        newMp
      }
    } else {
      val mp = create
      bufferedCache.put(name, (bufferSize, mp))
      mp
    }
  }

  def setToTile(name: String, tile: MutableArrayTile, weight: Double, rasterExtent: RasterExtent): Tile =
    name match {
      case PopulationName(d) =>
        val centers =
          populationCenters
            .map(_.mapGeom(_.buffer(d.toInt * 1000).as[Polygon].get))
        for(p <- centers if p.geom.intersects(rasterExtent.extent)) {
          rasterExtent.foreach(p.geom) { (col, row) =>
            val z = tile.getDouble(col, row) + (p.data.popDifSirtNormalized * 100 * weight)
            tile.setDouble(col, row, z)
          }
        }
        tile
      case AirStrikeName(d) =>
        val buffered =
          getBuffered("airstrikes", d.toInt * 1000) {
            airstrikesGeom.buffer(d.toInt * 1000).asMultiPolygon.get
          }
        bufferTile(tile, weight, d.toInt, buffered, rasterExtent)
      case WeaponRouteName(d) =>
        val buffered =
          getBuffered("weaponRoute", d.toInt * 1000) {
            weaponRouteGeom.buffer(d.toInt * 1000).asMultiPolygon.get
          }
        bufferTile(tile, weight, d.toInt, buffered, rasterExtent)
      case PeopleRouteName(d) =>
        val buffered =
          getBuffered("peopleRoute", d.toInt * 1000) {
            peopleRouteGeom.buffer(d.toInt * 1000).asMultiPolygon.get
          }
        bufferTile(tile, weight, d.toInt, buffered, rasterExtent)
      case DrugRouteName(d) =>
        val buffered =
          getBuffered("drugRoute", d.toInt * 1000) {
            drugRouteGeom.buffer(d.toInt * 1000).asMultiPolygon.get
          }
        bufferTile(tile, weight, d.toInt, buffered, rasterExtent)
      case RefineriesName(d) =>
        val buffered =
          getBuffered("refineries", d.toInt * 1000) {
            refineriesGeom.buffer(d.toInt * 1000).asMultiPolygon.get
          }
        bufferTile(tile, weight, d.toInt, buffered, rasterExtent)
      case IsAlliesName(d) =>
        val buffered =
          getBuffered("isAllies", d.toInt * 1000) {
            isAlliesGeom.buffer(d.toInt * 1000).asMultiPolygon.get
          }
        bufferTile(tile, weight, d.toInt, buffered, rasterExtent)
    }

  def read(fname: String): String =
    scala.io.Source.fromFile(s"/opt/data/catalog/$fname", "UTF-8")
      .getLines
      .mkString

  val libya: MultiPolygon =
    read("Libya_shape.geojson")
      .extractGeometries[MultiPolygon]
      .head
      .reproject(LatLng, WebMercator)

  val airstrikes =
    read("Airstrikes.geojson")
      .extractFeatures[PointFeature[AirStrike]]
      .map(_.mapGeom(_.reproject(LatLng, WebMercator)))
  assert(airstrikes.length > 0)
  val airstrikesGeom =
    MultiPoint(airstrikes.map(_.geom))

  val populationCenters =
    read("Libya_Geonames_wPopDiff.geojson")
      .extractFeatures[PointFeature[Population]]
      .map(_.mapGeom(_.reproject(LatLng, WebMercator)))
  assert(populationCenters.length > 0)

  val weaponRoute =
    read("LBY_weapon_route2_simplified.geojson")
      .extractGeometries[Line]
      .map(_.reproject(LatLng, WebMercator))
  assert(weaponRoute.length > 0)
  val weaponRouteGeom =
    MultiLine(weaponRoute)

  val peopleRoute =
    read("LBY_people_route2_simplified.geojson")
      .extractGeometries[Line]
      .map(_.reproject(LatLng, WebMercator))
  assert(peopleRoute.length > 0)
  val peopleRouteGeom =
    MultiLine(peopleRoute)

  val drugRoute =
    read("LBY_drug_route2.geojson")
      .extractGeometries[Line]
      .map(_.reproject(LatLng, WebMercator))
  assert(drugRoute.length > 0)
  val drugRouteGeom =
    MultiLine(drugRoute)

  val refineries =
    read("Refineries.geojson")
      .extractGeometries[Point]
      .map(_.reproject(LatLng, WebMercator))
  assert(refineries.length > 0)
  val refineriesGeom =
    MultiPoint(refineries)

  val isAllies =
    read("IS_Allies_encoding_fix.geojson")
      .extractGeometries[Point]
      .map(_.reproject(LatLng, WebMercator))
  assert(isAllies.length > 0)
  val isAlliesGeom =
    MultiPoint(isAllies)

  // This is what is rasterized in the pipeline layer
  // val oilPipelines =
  //   read("Oil_Pipeline.geojson")
  //     .extractGeometries[MultiLine]
  //     .map(_.reproject(LatLng, WebMercator))
  // assert(oilPipelines.length > 0)
}
