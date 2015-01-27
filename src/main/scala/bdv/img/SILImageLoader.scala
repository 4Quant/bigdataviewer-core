package bdv.img.sil

import bdv.AbstractViewerImgLoader
import bdv.img.cache._
import bdv.img.sil.SILVolatileIntArrayLoader
import mpicbg.spim.data.sequence.ViewId
import net.imglib2.RandomAccessibleInterval
import net.imglib2.`type`.NativeType
import net.imglib2.`type`.numeric.integer.IntType
import net.imglib2.`type`.volatiles.VolatileIntType
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileIntArray
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.util.Fraction
import tipl.spark.DSImg

class SILImageLoader(baseImg: DSImg[Int]) extends
  AbstractViewerImgLoader[IntType, VolatileIntType](new IntType(), new VolatileIntType()) {

  val dimensions = Array[Long](baseImg.getPos().x,
    baseImg.getPos().y,baseImg.getPos().z)

  val cache = new VolatileGlobalCellCache[VolatileIntArray](
    new SILVolatileIntArrayLoader(baseImg), 1, 1, 1, 10
  )

  val tileWidth = 100
  val tileHeight = 100

  override def getImage(view: ViewId, level: Int): RandomAccessibleInterval[IntType] = ???

  override def numMipmapLevels(setupId: Int): Int = ???

  override def getMipmapResolutions(setupId: Int): Array[Array[Double]] = ???

  override def getVolatileImage(view: ViewId, level: Int):
  RandomAccessibleInterval[VolatileIntType] = ???

  override def getMipmapTransforms(setupId: Int): Array[AffineTransform3D] = ???

  override def getCache: Cache = ???

  /**
   * (Almost) create a {@link CachedCellImg} backed by the cache.
   * The created image needs a {@link NativeImg#setLinkedType(net.imglib2.type.Type) linked type}
   * before it can be used.
   * The type should be either {@link ARGBType} and {@link VolatileARGBType}.
   */
  protected def prepareCachedImage[T <: NativeType[T]](view: ViewId, level: Int, loadingStrategy:
  LoadingStrategy): CachedCellImg[T, VolatileIntArray] = {


    val cellDimensions: Array[Int] = Array[Int](tileWidth, tileHeight, 1)
    val priority: Int = 0
    val cacheHints: CacheHints = new CacheHints(loadingStrategy, priority, false)
    val c: VolatileImgCells.CellCache[VolatileIntArray] = new
        VolatileGlobalCellCache[A]#VolatileCellCache(view.getTimePointId, view.getViewSetupId,
          level, cacheHints)
    val cells: VolatileImgCells[VolatileIntArray] = new VolatileImgCells[VolatileIntArray](c, new
        Fraction, dimensions, cellDimensions)
    new CachedCellImg[T, VolatileIntArray](cells)
  }
}