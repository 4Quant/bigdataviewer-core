package bdv.img.hdf5;

import static bdv.img.hdf5.Util.getResolutionsPath;
import static bdv.img.hdf5.Util.getSubdivisionsPath;
import static bdv.img.hdf5.Util.reorder;
import static mpicbg.spim.data.XmlHelpers.loadPath;

import java.io.File;
import java.util.ArrayList;

import mpicbg.spim.data.View;
import mpicbg.spim.data.XmlHelpers;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.NativeImg;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.img.cell.CellImg;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.integer.VolatileUnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;

import org.jdom2.Element;

import bdv.ViewerImgLoader;
import bdv.img.cache.VolatileCell;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.img.cache.VolatileGlobalCellCache.LoadingStrategy;
import bdv.img.cache.VolatileImgCells;
import bdv.img.cache.VolatileImgCells.CellCache;
import ch.systemsx.cisd.hdf5.HDF5DataSetInformation;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;

public class Hdf5ImageLoader implements ViewerImgLoader
{
	protected File hdf5File;

	protected IHDF5Reader hdf5Reader;

	protected VolatileGlobalCellCache< VolatileShortArray > cache;

	protected final ArrayList< double[][] > perSetupMipmapResolutions;

	protected final ArrayList< int[][] > perSetupSubdivisions;

	/**
	 * List of partitions if the dataset is split across several files
	 */
	protected final ArrayList< Partition > partitions;

	protected int[] maxLevels;

	protected final boolean isCoarsestLevelBlocking = true;

	public Hdf5ImageLoader()
	{
		this( null );
	}

	public Hdf5ImageLoader( final ArrayList< Partition > hdf5Partitions )
	{
		hdf5File = null;
		hdf5Reader = null;
		cache = null;
		perSetupMipmapResolutions = new ArrayList< double[][] >();
		perSetupSubdivisions = new ArrayList< int[][] >();
		partitions = new ArrayList< Partition >();
		if ( hdf5Partitions != null )
			partitions.addAll( hdf5Partitions );
		maxLevels = null;
	}

	public Hdf5ImageLoader( final File hdf5File, final ArrayList< Partition > hdf5Partitions )
	{
		this( hdf5File, hdf5Partitions, true );
	}

	public Hdf5ImageLoader( final File hdf5File, final ArrayList< Partition > hdf5Partitions, final boolean doOpen )
	{
		this.hdf5File = hdf5File;
		perSetupMipmapResolutions = new ArrayList< double[][] >();
		perSetupSubdivisions = new ArrayList< int[][] >();
		partitions = new ArrayList< Partition >();
		if ( hdf5Partitions != null )
			partitions.addAll( hdf5Partitions );
		if ( doOpen )
			open();
	}

	private void open()
	{
		hdf5Reader = HDF5Factory.openForReading( hdf5File );
		final int numTimepoints = hdf5Reader.readInt( "numTimepoints" );
		final int numSetups = hdf5Reader.readInt( "numSetups" );

		int maxNumLevels = 0;
		maxLevels = new int[ numSetups ];
		perSetupMipmapResolutions.clear();
		perSetupSubdivisions.clear();
		for ( int setup = 0; setup < numSetups; ++setup )
		{
			final double [][] mipmapResolutions = hdf5Reader.readDoubleMatrix( getResolutionsPath( setup ) );
			perSetupMipmapResolutions.add( mipmapResolutions );
			if ( mipmapResolutions.length > maxNumLevels )
				maxNumLevels = mipmapResolutions.length;
			maxLevels[ setup ] = mipmapResolutions.length - 1;

			final int [][] subdivisions = hdf5Reader.readIntMatrix( getSubdivisionsPath( setup ) );
			perSetupSubdivisions.add( subdivisions );
		}

		cache = new VolatileGlobalCellCache< VolatileShortArray >( new Hdf5VolatileShortArrayLoader( hdf5Reader ), numTimepoints, numSetups, maxNumLevels, maxLevels );
	}

	@Override
	public void init( final Element elem, final File basePath )
	{
		String path;
		try
		{
			path = loadPath( elem, "hdf5", basePath ).toString();
			partitions.clear();
			for ( final Element p : elem.getChildren( "partition" ) )
				partitions.add( new Partition( p, basePath ) );
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
		hdf5File = new File( path );
		open();
	}

	@Override
	public Element toXml( final File basePath )
	{
		final Element elem = new Element( "ImageLoader" );
		elem.setAttribute( "class", getClass().getCanonicalName() );
		elem.addContent( XmlHelpers.pathElement( "hdf5", hdf5File, basePath ) );
		for ( final Partition partition : partitions )
			elem.addContent( partition.toXml( basePath ) );
		return elem;
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
	public RandomAccessibleInterval< FloatType > getImage( final View view )
	{
		throw new UnsupportedOperationException( "currently not used" );
	}

	@Override
	public CellImg< UnsignedShortType, VolatileShortArray, VolatileCell< VolatileShortArray > > getUnsignedShortImage( final View view )
	{
		return getUnsignedShortImage( view, 0 );
	}

	@Override
	public CellImg< UnsignedShortType, VolatileShortArray, VolatileCell< VolatileShortArray > > getUnsignedShortImage( final View view, final int level )
	{
		final CellImg< UnsignedShortType, VolatileShortArray, VolatileCell< VolatileShortArray > >  img = prepareCachedImage( view, level, LoadingStrategy.BLOCKING );
		final UnsignedShortType linkedType = new UnsignedShortType( img );
		img.setLinkedType( linkedType );
		return img;
	}

	@Override
	public CellImg< VolatileUnsignedShortType, VolatileShortArray, VolatileCell< VolatileShortArray > > getVolatileUnsignedShortImage( final View view, final int level )
	{
//		final CellImg< VolatileUnsignedShortType, VolatileShortArray, Hdf5Cell< VolatileShortArray > >  img = prepareCachedImage( view, level, LoadingStrategy.VOLATILE );
		final CellImg< VolatileUnsignedShortType, VolatileShortArray, VolatileCell< VolatileShortArray > >  img = prepareCachedImage( view, level, LoadingStrategy.BUDGETED );
		final VolatileUnsignedShortType linkedType = new VolatileUnsignedShortType( img );
		img.setLinkedType( linkedType );
		return img;
	}

	public VolatileGlobalCellCache< VolatileShortArray > getCache()
	{
		return cache;
	}

	@Override
	public double[][] getMipmapResolutions( final int setup )
	{
		return perSetupMipmapResolutions.get( setup );
	}

	public int[][] getSubdivisions( final int setup )
	{
		return perSetupSubdivisions.get( setup );
	}

	@Override
	public int numMipmapLevels( final int setup )
	{
		return getMipmapResolutions( setup ).length;
	}

	/**
	 * (Almost) create a {@link CellImg} backed by the cache.
	 * The created image needs a {@link NativeImg#setLinkedType(net.imglib2.type.Type) linked type} before it can be used.
	 * The type should be either {@link UnsignedShortType} and {@link VolatileUnsignedShortType}.
	 */
	protected < T extends NativeType< T > > CellImg< T, VolatileShortArray, VolatileCell< VolatileShortArray > > prepareCachedImage( final View view, final int level, final LoadingStrategy loadingStrategy )
	{
		if ( hdf5Reader == null )
			throw new RuntimeException( "no hdf5 file open" );

		final String cellsPath = Util.getCellsPath( view, level );
		final HDF5DataSetInformation info;
		synchronized ( hdf5Reader )
		{
			info = hdf5Reader.getDataSetInformation( cellsPath );
		}
		final long[] dimensions = reorder( info.getDimensions() );
		final int[] cellDimensions = reorder( info.tryGetChunkSizes() );

		final CellCache< VolatileShortArray > c = cache.new Hdf5CellCache( view.getTimepointIndex(), view.getSetupIndex(), level, loadingStrategy );
		final VolatileImgCells< VolatileShortArray > cells = new VolatileImgCells< VolatileShortArray >( c, 1, dimensions, cellDimensions );
		final CellImg< T, VolatileShortArray, VolatileCell< VolatileShortArray > > img = new CellImg< T, VolatileShortArray, VolatileCell< VolatileShortArray > >( null, cells );
		return img;
	}
}
