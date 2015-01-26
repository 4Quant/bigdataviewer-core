package bdv

import org.scalatest.FunSuite
import tipl.spark.SparkGlobal

class BDVSparkTests extends FunSuite {
  lazy val sc = SparkGlobal.getContext("BDV Tests")
  test("Create numeric RDD") {

  }
}