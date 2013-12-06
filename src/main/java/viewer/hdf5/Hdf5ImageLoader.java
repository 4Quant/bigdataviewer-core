package viewer.hdf5;

import static mpicbg.spim.data.XmlHelpers.loadPath;
import static viewer.hdf5.Util.getResolutionsPath;
import static viewer.hdf5.Util.getSubdivisionsPath;
import static viewer.hdf5.Util.reorder;

import java.io.File;
import java.util.ArrayList;

import mpicbg.spim.data.View;
import mpicbg.spim.data.XmlHelpers;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.cell.CellImg;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.natives.VolatileShortArray;
import net.imglib2.type.volatiles.natives.VolatileUnsignedShortType;

import org.jdom2.Element;

import viewer.ViewerImgLoader;
import viewer.hdf5.img.Hdf5Cell;
import viewer.hdf5.img.Hdf5GlobalCellCache;
import viewer.hdf5.img.Hdf5GlobalCellCache.Hdf5BlockingCellCache;
import viewer.hdf5.img.Hdf5GlobalCellCache.Hdf5CellCache;
import viewer.hdf5.img.Hdf5ImgCells;
import viewer.hdf5.img.VolatileShortArrayLoader;
import ch.systemsx.cisd.hdf5.HDF5DataSetInformation;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;

public class Hdf5ImageLoader implements ViewerImgLoader
{
	protected File hdf5File;

	protected IHDF5Reader hdf5Reader;

	protected Hdf5GlobalCellCache< VolatileShortArray > cache;

	protected final ArrayList< double[][] > perSetupMipmapResolutions;

	protected final ArrayList< int[][] > perSetupSubdivisions;

	/**
	 * List of partitions if the dataset is split across several files
	 */
	protected final ArrayList< Partition > partitions;

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
		final int[] maxLevels = new int[ numSetups ];
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


		cache = new Hdf5GlobalCellCache< VolatileShortArray >( new VolatileShortArrayLoader( hdf5Reader ), numTimepoints, numSetups, maxNumLevels, maxLevels );
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
	public CellImg< UnsignedShortType, VolatileShortArray, Hdf5Cell< VolatileShortArray > > getUnsignedShortImage( final View view )
	{
		return getUnsignedShortImage( view, 0 );
	}

	@Override
	public CellImg< UnsignedShortType, VolatileShortArray, Hdf5Cell< VolatileShortArray > > getUnsignedShortImage( final View view, final int level )
	{
		if ( hdf5Reader == null )
			throw new RuntimeException( "no hdf5 file open" );

		synchronized ( hdf5Reader )
		{
			final String cellsPath = Util.getCellsPath( view, level );
//			System.out.println( "loading " + cellsPath );
			final HDF5DataSetInformation info = hdf5Reader.getDataSetInformation( cellsPath );
			final long[] dimensions = reorder( info.getDimensions() );
			final int[] cellDimensions = reorder( info.tryGetChunkSizes() );

			final Hdf5GlobalCellCache< VolatileShortArray >.Hdf5BlockingCellCache c = cache.new Hdf5BlockingCellCache( view.getTimepointIndex(), view.getSetupIndex(), level );
			final Hdf5ImgCells< VolatileShortArray > cells = new Hdf5ImgCells< VolatileShortArray >( c, 1, dimensions, cellDimensions );
			final CellImgFactory< UnsignedShortType > factory = null;
			final CellImg< UnsignedShortType, VolatileShortArray, Hdf5Cell< VolatileShortArray > > img = new CellImg< UnsignedShortType, VolatileShortArray, Hdf5Cell< VolatileShortArray > >( factory, cells );
			final UnsignedShortType linkedType = new UnsignedShortType( img );
			img.setLinkedType( linkedType );

			return img;
		}
	}

	public Hdf5GlobalCellCache< VolatileShortArray > getCache()
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

	@Override
	public CellImg< VolatileUnsignedShortType, VolatileShortArray, Hdf5Cell< VolatileShortArray > > getVolatileUnsignedShortImage( final View view, final int level )
	{
		if ( hdf5Reader == null )
			throw new RuntimeException( "no hdf5 file open" );

		synchronized ( hdf5Reader )
		{
			final String cellsPath = Util.getCellsPath( view, level );
//			System.out.println( "loading " + cellsPath );
			final HDF5DataSetInformation info = hdf5Reader.getDataSetInformation( cellsPath );
			final long[] dimensions = reorder( info.getDimensions() );
			final int[] cellDimensions = reorder( info.tryGetChunkSizes() );

			final Hdf5GlobalCellCache< VolatileShortArray >.Hdf5CellCache c = cache.new Hdf5CellCache( view.getTimepointIndex(), view.getSetupIndex(), level );
			final Hdf5ImgCells< VolatileShortArray > cells = new Hdf5ImgCells< VolatileShortArray >( c, 1, dimensions, cellDimensions );
			final CellImgFactory< VolatileUnsignedShortType > factory = null;
			final CellImg< VolatileUnsignedShortType, VolatileShortArray, Hdf5Cell< VolatileShortArray > > img = new CellImg< VolatileUnsignedShortType, VolatileShortArray, Hdf5Cell< VolatileShortArray > >( factory, cells );
			final VolatileUnsignedShortType linkedType = new VolatileUnsignedShortType( img );
			img.setLinkedType( linkedType );

			return img;
		}
	}
}
