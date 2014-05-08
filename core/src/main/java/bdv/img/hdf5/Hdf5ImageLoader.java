package bdv.img.hdf5;

import static bdv.img.hdf5.Util.getResolutionsPath;
import static bdv.img.hdf5.Util.getSubdivisionsPath;
import static bdv.img.hdf5.Util.reorder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.NativeImg;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.img.cell.CellImg;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.sampler.special.ConstantRandomAccessible;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;
import net.imglib2.view.Views;
import bdv.AbstractViewerImgLoader;
import bdv.img.cache.CacheHints;
import bdv.img.cache.CachedCellImg;
import bdv.img.cache.LoadingStrategy;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.img.cache.VolatileImgCells;
import bdv.img.cache.VolatileImgCells.CellCache;
import bdv.util.MipmapTransforms;
import ch.systemsx.cisd.hdf5.HDF5DataSetInformation;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;

public class Hdf5ImageLoader extends AbstractViewerImgLoader< UnsignedShortType, VolatileUnsignedShortType >
{
	protected File hdf5File;

	protected IHDF5Reader hdf5Reader;

	protected VolatileGlobalCellCache< VolatileShortArray > cache;

	// TODO clean up after spim_data switch
	public static class MipmapInfo
	{
		private final double[][] resolutions;

		private final AffineTransform3D[] transforms;

		private final int[][] subdivisions;

		private final int maxLevel;

		public MipmapInfo( final double[][] resolutions, final AffineTransform3D[] transforms, final int[][] subdivisions )
		{
			this.resolutions = resolutions;
			this.transforms = transforms;
			this.subdivisions = subdivisions;
			this.maxLevel = resolutions.length - 1;
		}

		public double[][] getResolutions()
		{
			return resolutions;
		}

		public AffineTransform3D[] getTransforms()
		{
			return transforms;
		}

		public int[][] getSubdivisions()
		{
			return subdivisions;
		}

		public int getMaxLevel()
		{
			return maxLevel;
		}

		public int getNumLevels()
		{
			return maxLevel + 1;
		}
	}

	// TODO clean up after spim_data switch
	protected final HashMap< Integer, MipmapInfo > perSetupMipmapInfo;

	/**
	 * List of partitions if the dataset is split across several files
	 */
	protected final ArrayList< Partition > partitions;

	protected int maxNumLevels;

	/**
	 * TODO adapt comment after spim_data switch
	 *
	 *
	 * An array of long[] arrays with {@link #numTimepoints} *
	 * {@link #numSetups} * {@link #maxNumLevels} entries. Every entry is either
	 * null or the dimensions of one image (identified by flattened index of
	 * level, setup, and timepoint). This is either loaded from XML if present
	 * or otherwise filled in when an image is loaded for the first time.
	 */
	protected final HashMap< ViewLevelId, long[] > cachedDimensions;
// TODO spim_data. cachedDimensions and cachedExistence should be a single HashMap< ViewLevelId, DimAndExistence >

	/**
	 * TODO adapt comment after spim_data switch
	 *
	 *
	 *
	 * An array of Booleans with {@link #numTimepoints} *
	 * {@link #numSetups} * {@link #maxNumLevels} entries. Every entry is either
	 * null or the existence of one image (identified by flattened index of
	 * level, setup, and timepoint). This is either loaded from XML if present
	 * or otherwise filled in when an image is loaded for the first time.
	 */
	protected final HashMap< ViewLevelId, Boolean > cachedExistence;

	protected final AbstractSequenceDescription< ?, ?, ? > sequenceDescription;

	/**
	 *
	 * @param hdf5File
	 * @param hdf5Partitions
	 * @param sequenceDescription
	 *            the {@link AbstractSequenceDescription}. When
	 *            {@link #getImage(ViewId) loading} images, this may be used to
	 *            retrieve additional information for a {@link ViewId}, such as
	 *            setup name, {@link Angle}, {@link Channel}, etc.
	 */
	public Hdf5ImageLoader( final File hdf5File, final ArrayList< Partition > hdf5Partitions, final AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		this( hdf5File, hdf5Partitions, sequenceDescription, true );
	}

	public Hdf5ImageLoader( final File hdf5File, final ArrayList< Partition > hdf5Partitions, final AbstractSequenceDescription< ?, ?, ? > sequenceDescription, final boolean doOpen )
	{
		super( new UnsignedShortType(), new VolatileUnsignedShortType() );
		this.hdf5File = hdf5File;
		perSetupMipmapInfo = new HashMap< Integer, MipmapInfo >();
		cachedDimensions = new HashMap< ViewLevelId, long[] >();
		cachedExistence = new HashMap< ViewLevelId, Boolean >();
		this.sequenceDescription = sequenceDescription;
		partitions = new ArrayList< Partition >();
		if ( hdf5Partitions != null )
			partitions.addAll( hdf5Partitions );
		if ( doOpen )
			open();
	}

	private boolean isOpen = false;

	private void open()
	{
		if ( ! isOpen )
		{
			synchronized ( this )
			{
				if ( isOpen )
					return;
				isOpen = true;

				hdf5Reader = HDF5Factory.openForReading( hdf5File );

				maxNumLevels = 0;
				perSetupMipmapInfo.clear();
				final List< ? extends BasicViewSetup > setups = sequenceDescription.getViewSetupsOrdered();
				for ( final BasicViewSetup setup : setups )
				{
					final int setupId = setup.getId();

					final double[][] resolutions = hdf5Reader.readDoubleMatrix( getResolutionsPath( setupId ) );
					final AffineTransform3D[] transforms = new AffineTransform3D[ resolutions.length ];
					for ( int level = 0; level < resolutions.length; level++ )
						transforms[ level ] = MipmapTransforms.getMipmapTransformDefault( resolutions[ level ] );
					final int[][] subdivisions = hdf5Reader.readIntMatrix( getSubdivisionsPath( setupId ) );

					if ( resolutions.length > maxNumLevels )
						maxNumLevels = resolutions.length;

					perSetupMipmapInfo.put( setupId, new MipmapInfo( resolutions, transforms, subdivisions ) );
				}

				cachedDimensions.clear();
				cachedExistence.clear();

				final List< TimePoint > timepoints = sequenceDescription.getTimePoints().getTimePointsOrdered();
				final int maxNumTimepoints = timepoints.get( timepoints.size() - 1 ).getId() + 1;
				final int maxNumSetups = setups.get( setups.size() - 1 ).getId() + 1;
				cache = new VolatileGlobalCellCache< VolatileShortArray >( new Hdf5VolatileShortArrayLoader( hdf5Reader ), maxNumTimepoints, maxNumSetups, maxNumLevels, 1 );
			}
		}
	}

	public void initCachedDimensionsFromHdf5( final boolean background )
	{
		open();
		final long t0 = System.currentTimeMillis();
		final List< TimePoint > timepoints = sequenceDescription.getTimePoints().getTimePointsOrdered();
		final List< ? extends BasicViewSetup > setups = sequenceDescription.getViewSetupsOrdered();
		for ( final TimePoint timepoint : timepoints )
		{
			final int t = timepoint.getId();
			for ( final BasicViewSetup setup : setups )
			{
				final int s = setup.getId();
				final int numLevels = perSetupMipmapInfo.get( s ).getNumLevels();
				for ( int l = 0; l < numLevels; ++l )
					getImageDimension( new ViewLevelId( t, s, l ) );
			}
			if ( background )
				synchronized ( this )
				{
					try
					{
						wait( 100 );
					}
					catch ( final InterruptedException e )
					{}
				}
		}
		final long t1 = System.currentTimeMillis() - t0;
		System.out.println( "initCachedDimensionsFromHdf5 : " + t1 + " ms" );
	}

	public File getHdf5File()
	{
		return hdf5File;
	}

	public ArrayList< Partition > getPartitions()
	{
		return partitions;
	}

	@Override
	public RandomAccessibleInterval< UnsignedShortType > getImage( final ViewId view, final int level )
	{
		final ViewLevelId id = new ViewLevelId( view, level );
		if ( ! existsImageData( id ) )
		{
			System.err.println(	String.format(
					"image data for timepoint %d setup %d level %d could not be found. Partition file missing?",
					id.getTimePointId(), id.getViewSetupId(), id.getLevel() ) );
			return getMissingDataImage( id, new UnsignedShortType() );
		}
		final CachedCellImg< UnsignedShortType, VolatileShortArray >  img = prepareCachedImage( id, LoadingStrategy.BLOCKING );
		final UnsignedShortType linkedType = new UnsignedShortType( img );
		img.setLinkedType( linkedType );
		return img;
	}

	@Override
	public RandomAccessibleInterval< VolatileUnsignedShortType > getVolatileImage( final ViewId view, final int level )
	{
		final ViewLevelId id = new ViewLevelId( view, level );
		if ( ! existsImageData( id ) )
		{
			System.err.println(	String.format(
					"image data for timepoint %d setup %d level %d could not be found. Partition file missing?",
					id.getTimePointId(), id.getViewSetupId(), id.getLevel() ) );
			return getMissingDataImage( id, new VolatileUnsignedShortType() );
		}
		final CachedCellImg< VolatileUnsignedShortType, VolatileShortArray >  img = prepareCachedImage( id, LoadingStrategy.BUDGETED );
		final VolatileUnsignedShortType linkedType = new VolatileUnsignedShortType( img );
		img.setLinkedType( linkedType );
		return img;
	}

	@Override
	public VolatileGlobalCellCache< VolatileShortArray > getCache()
	{
		open();
		return cache;
	}

	// TODO: spim_data. should this and the following methods be replaced by single method getMipmapInfo()?
	@Override
	public double[][] getMipmapResolutions( final int setupId )
	{
		open();
		return perSetupMipmapInfo.get( setupId ).getResolutions();
	}

	@Override
	public AffineTransform3D[] getMipmapTransforms( final int setupId )
	{
		open();
		return perSetupMipmapInfo.get( setupId ).getTransforms();
	}

	public int[][] getSubdivisions( final int setupId )
	{
		open();
		return perSetupMipmapInfo.get( setupId ).getSubdivisions();
	}

	@Override
	public int numMipmapLevels( final int setupId )
	{
		open();
		return perSetupMipmapInfo.get( setupId ).getNumLevels();
	}

	/**
	 * Checks whether the given image data is present in the hdf5. Missing data
	 * may be caused by missing partition files
	 *
	 * @return true, if the given image data is present.
	 */
	public boolean existsImageData( final ViewLevelId id )
	{
		final Boolean exists = cachedExistence.get( id );
		if ( exists == null )
			// will set cachedExistence[ index ] as a side effect
			getImageDimension( id );
		return cachedExistence.get( id );
	}

	/**
	 * For images that are missing in the hdf5, a constant image is created.
	 * If the dimension of the missing image is present in {@link #cachedDimensions} then use that.
	 * Otherwise create a 1x1x1 image.
	 */
	protected < T > RandomAccessibleInterval< T > getMissingDataImage( final ViewLevelId id, final T constant )
	{
		long[] d = cachedDimensions.get( id );
		if ( d == null )
			d = new long[] { 1, 1, 1 };
		return Views.interval( new ConstantRandomAccessible< T >( constant, 3 ), new FinalInterval( d ) );
	}

	public long[] getImageDimension( final ViewLevelId id )
	{
		open();
		long[] dims = cachedDimensions.get( id );
		if ( dims == null )
		{
			final String cellsPath = Util.getCellsPath( id );
			HDF5DataSetInformation info = null;
			boolean exists = false;
			// pause Fetcher threads for 5 ms. There will be more calls to
			// getImageDimension() because this happens when a timepoint is
			// loaded, and all setups for the timepoint are loaded then. We
			// don't want to interleave this with block loading operations.
			cache.pauseFetcherThreadsFor( 5 );
			synchronized ( hdf5Reader )
			{
				try {
					info = hdf5Reader.getDataSetInformation( cellsPath );
					exists = true;
				} catch ( final Exception e ) {
				}
			}
			if ( exists )
				dims = reorder( info.getDimensions() );
			else
				dims = new long[] { 1, 1, 1 };
			cachedExistence.put( id, exists );
			cachedDimensions.put( id, dims );
		}
		return dims;
	}

	/**
	 * (Almost) create a {@link CellImg} backed by the cache.
	 * The created image needs a {@link NativeImg#setLinkedType(net.imglib2.type.Type) linked type} before it can be used.
	 * The type should be either {@link UnsignedShortType} and {@link VolatileUnsignedShortType}.
	 */
	protected < T extends NativeType< T > > CachedCellImg< T, VolatileShortArray > prepareCachedImage( final ViewLevelId id, final LoadingStrategy loadingStrategy )
	{
		open();
		final int timepointId = id.getTimePointId();
		final int setupId = id.getViewSetupId();
		final int level = id.getLevel();
		final MipmapInfo mipmapInfo = perSetupMipmapInfo.get( setupId );

		final long[] dimensions = getImageDimension( id );
		final int[] cellDimensions = mipmapInfo.getSubdivisions()[ level ];

		final int priority = mipmapInfo.getMaxLevel() - level;
		final CacheHints cacheHints = new CacheHints( loadingStrategy, priority, false );
		final CellCache< VolatileShortArray > c = cache.new VolatileCellCache( timepointId, setupId, level, cacheHints );
		final VolatileImgCells< VolatileShortArray > cells = new VolatileImgCells< VolatileShortArray >( c, 1, dimensions, cellDimensions );
		final CachedCellImg< T, VolatileShortArray > img = new CachedCellImg< T, VolatileShortArray >( cells );
		return img;
	}

	public void printMipmapInfo()
	{
		open();
		for ( final BasicViewSetup setup : sequenceDescription.getViewSetupsOrdered() )
		{
			final int setupId = setup.getId();
			System.out.println( "setup " + setupId );
			final MipmapInfo mipmapInfo = perSetupMipmapInfo.get( setupId );
			final double[][] reslevels = mipmapInfo.getResolutions();
			final int[][] subdiv = mipmapInfo.getSubdivisions();
			final int numLevels = mipmapInfo.getNumLevels();
			System.out.println( "    resolutions:");
			for ( int level = 0; level < numLevels; ++level )
			{
				final double[] res = reslevels[ level ];
				System.out.println( "    " + level + ": " + net.imglib2.util.Util.printCoordinates( res ) );
			}
			System.out.println( "    subdivisions:");
			for ( int level = 0; level < numLevels; ++level )
			{
				final int[] res = subdiv[ level ];
				System.out.println( "    " + level + ": " + net.imglib2.util.Util.printCoordinates( res ) );
			}
		}
	}
}
