package bdv.img.sil

import bdv.img.cache.CacheArrayLoader
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileIntArray
import tipl.util.TImgTools

// SIL Imports
import tipl.spark.DSImg

class SILVolatileIntArrayLoader(baseImg: DSImg[Int])
  extends CacheArrayLoader[VolatileIntArray] {

  val basePos = baseImg.getPos()

  private var theEmptyArray: VolatileIntArray =
    new VolatileIntArray(tileWidth * tileHeight, false)
  private final val tileWidth: Int = 0
  private final val tileHeight: Int = 0

  def getBytesPerElement: Int = 4

  @throws(classOf[InterruptedException])
  def loadArray(timepoint: Int, setup: Int, level: Int, dimensions: Array[Int], min: Array[Long])
  : VolatileIntArray = {

    val data = baseImg.getPolyImage(min(2).toInt,TImgTools.IMAGETYPE_INT).asInstanceOf[Array[Int]]

    return new VolatileIntArray(data, true)
  }

  def emptyArray(dimensions: Array[Int]): VolatileIntArray = {
    val numEntities: Int = dimensions.fold(1)(_ * _)

    if (theEmptyArray.getCurrentStorageArray.length < numEntities) theEmptyArray =
      new VolatileIntArray(numEntities, false)
    return theEmptyArray
  }
}