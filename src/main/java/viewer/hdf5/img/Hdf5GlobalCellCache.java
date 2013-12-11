package viewer.hdf5.img;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import net.imglib2.img.basictypeaccess.volatiles.VolatileAccess;
import viewer.hdf5.img.Hdf5ImgCells.CellCache;
import viewer.util.ThreadManager;

public class Hdf5GlobalCellCache< A extends VolatileAccess >
{
	final int numTimepoints;

	final int numSetups;

	final int maxNumLevels;

	final int[] maxLevels;

	class Key
	{
		final int timepoint;

		final int setup;

		final int level;

		final int index;

		public Key( final int timepoint, final int setup, final int level, final int index )
		{
			this.timepoint = timepoint;
			this.setup = setup;
			this.level = level;
			this.index = index;

			final long value = ( ( index * maxNumLevels + level ) * numSetups + setup ) * numTimepoints + timepoint;
			hashcode = ( int ) ( value ^ ( value >>> 32 ) );
		}

		@Override
		public boolean equals( final Object other )
		{
			if ( this == other )
				return true;
			if ( !( other instanceof Hdf5GlobalCellCache.Key ) )
				return false;
			@SuppressWarnings( "unchecked" )
			final Key that = ( Key ) other;
			return ( this.timepoint == that.timepoint ) && ( this.setup == that.setup ) && ( this.level == that.level ) && ( this.index == that.index );
		}

		final int hashcode;

		@Override
		public int hashCode()
		{
			return hashcode;
		}
	}

	class Entry
	{
		final protected Key key;

		protected Hdf5Cell< A > data;

		protected long enqueueFrame;

		public Entry( final Key key, final Hdf5Cell< A > data )
		{
			this.key = key;
			this.data = data;
			enqueueFrame = -1;
		}

		@Override
		public void finalize()
		{
			synchronized ( softReferenceCache )
			{
				// System.out.println( "finalizing..." );
				softReferenceCache.remove( key );
				// System.out.println( softReferenceCache.size() +
				// " tiles chached." );
			}
		}
	}

	final protected ConcurrentHashMap< Key, Reference< Entry > > softReferenceCache = new ConcurrentHashMap< Key, Reference< Entry > >();

	final protected BlockingFetchQueues< Key > queue;

	protected long currentQueueFrame = 0;

	/**
	 * Load the data for the {@link Hdf5Cell} referenced by k, if
	 * <ul>
	 * <li>the {@link Hdf5Cell} is in the cache, and
	 * <li>the data is not yet loaded (valid).
	 * </ul>
	 *
	 * @param k
	 */
	protected void loadIfNotValid( final Key k )
	{
		final Reference< Entry > ref = softReferenceCache.get( k );
		if ( ref != null )
		{
			final Entry entry = ref.get();
			if ( entry != null )
				loadEntryIfNotValid( entry );
		}
	}

	class Fetcher extends Thread
	{
		@Override
		final public void run()
		{
			while ( !isInterrupted() )
			{
				try
				{
					while ( pause )
						synchronized ( this )
						{
							pause = false;
							wait( 1 );
						}
//					System.out.println("->fetcher");
					loadIfNotValid( queue.take() );
//					Thread.sleep(1);
				}
				catch ( final InterruptedException e )
				{
					break;
				}
			}
		}

		private volatile boolean pause = false;

		public void pause()
		{
			pause = true;
		}
	}

	public void pause()
	{
		for ( final Fetcher f : fetchers )
			f.pause();
	}

	final protected ArrayList< Fetcher > fetchers;

	private final ThreadManager threadManager;

	final protected Hdf5ArrayLoader< A > loader;

	public Hdf5GlobalCellCache( final Hdf5ArrayLoader< A > loader, final int numTimepoints, final int numSetups, final int maxNumLevels, final int[] maxLevels )
	{
		this.loader = loader;
		this.numTimepoints = numTimepoints;
		this.numSetups = numSetups;
		this.maxNumLevels = maxNumLevels;
		this.maxLevels = maxLevels;

		queue = new BlockingFetchQueues< Key >( maxNumLevels );
		threadManager = new ThreadManager();
		fetchers = new ArrayList< Fetcher >();
		for ( int i = 0; i < 2; ++i ) // TODO: add numFetcherThreads parameter
		{
			final Fetcher f = new Fetcher();
			fetchers.add( f );
			threadManager.addThread( f );
			f.start();
		}
	}

	/**
	 * Get a cell if it is in the cache or null. Note, that a cell being in the
	 * cache only means that here is a data array, but not necessarily that the
	 * data has already been loaded. If the cell's cache entry has not been
	 * enqueued for loading in the current frame yet, it is enqueued.
	 *
	 * @return a cell with the specified coordinates or null.
	 */
	public Hdf5Cell< A > getGlobalIfCached( final int timepoint, final int setup, final int level, final int index )
	{
		final Key k = new Key( timepoint, setup, level, index );
		final Reference< Entry > ref = softReferenceCache.get( k );
		if ( ref != null )
		{
			final Entry entry = ref.get();
			if ( entry != null )
			{
				if ( entry.enqueueFrame < currentQueueFrame )
				{
					entry.enqueueFrame = currentQueueFrame;
					queue.put( k, maxLevels[ setup ] - level );
				}
				return entry.data;
			}
		}
		return null;
	}

	/**
	 * Get a cell if it is in the cache or null. If a cell is returned, it is
	 * guaranteed to have valid data. If necessary this call will block until
	 * the data is loaded.
	 *
	 * @return a valid cell with the specified coordinates or null.
	 */
	public Hdf5Cell< A > getGlobalIfCachedAndLoadBlocking( final int timepoint, final int setup, final int level, final int index )
	{
		final Key k = new Key( timepoint, setup, level, index );
		final Reference< Entry > ref = softReferenceCache.get( k );
		if ( ref != null )
		{
			final Entry entry = ref.get();
			if ( entry != null )
			{
				if ( !entry.data.getData().isValid() )
					pause();
				loadEntryIfNotValid( entry );
				return entry.data;
			}
		}
		return null;
	}

	/**
	 * Create a new cell with the specified coordinates, if it isn't in the
	 * cache already. Enqueue the cell for loading.
	 *
	 * @return a cell with the specified coordinates.
	 */
	public synchronized Hdf5Cell< A > createGlobal( final int[] cellDims, final long[] cellMin, final int timepoint, final int setup, final int level, final int index )
	{
		final Key k = new Key( timepoint, setup, level, index );
		final Reference< Entry > ref = softReferenceCache.get( k );
		if ( ref != null )
		{
			final Entry entry = ref.get();
			if ( entry != null )
				return entry.data;
		}

		final Hdf5Cell< A > cell = new Hdf5Cell< A >( cellDims, cellMin, loader.emptyArray( cellDims ) );
		softReferenceCache.put( k, new WeakReference< Entry >( new Entry( k, cell ) ) );
		queue.put( k, maxLevels[ setup ] - level );

		return cell;
	}

	/**
	 * Create a new cell with the specified coordinates, if it isn't in the
	 * cache already. Block until the data for the cell has been loaded.
	 *
	 * @return a valid cell with the specified coordinates.
	 */
	public Hdf5Cell< A > createGlobalAndLoadBlocking( final int[] cellDims, final long[] cellMin, final int timepoint, final int setup, final int level, final int index )
	{
		final Key k = new Key( timepoint, setup, level, index );
		Entry entry = null;

		final Reference< Entry > ref = softReferenceCache.get( k );
		if ( ref != null )
			entry = ref.get();
		if ( entry == null )
		{
			final Hdf5Cell< A > cell = new Hdf5Cell< A >( cellDims, cellMin, loader.emptyArray( cellDims ) );
			entry = new Entry( k, cell );
			softReferenceCache.put( k, new SoftReference< Entry >( entry ) );
		}

		pause();
		loadEntryIfNotValid( entry );
		return entry.data;
	}

	/**
	 * Load the data for the {@link Entry}, if it is not yet loaded (valid).
	 */
	protected void loadEntryIfNotValid( final Entry entry )
	{
		final Hdf5Cell< A > c = entry.data;
		if ( !c.getData().isValid() )
		{
			final int[] cellDims = c.getDimensions();
			final long[] cellMin = c.getMin();
			final Key k = entry.key;
			final int timepoint = k.timepoint;
			final int setup = k.setup;
			final int level = k.level;
			synchronized( loader )
			{
				if ( !entry.data.getData().isValid() )
				{
					final Hdf5Cell< A > cell = new Hdf5Cell< A >( cellDims, cellMin, loader.loadArray( timepoint, setup, level, cellDims, cellMin ) );
					entry.data = cell; // TODO: need to synchronize or make entry.data volatile?
					softReferenceCache.put( entry.key, new SoftReference< Entry >( entry ) );
				}
			}
		}
	}

	public class Hdf5CellCache implements CellCache< A >
	{
		final int timepoint;

		final int setup;

		final int level;

		public Hdf5CellCache( final int timepoint, final int setup, final int level )
		{
			this.timepoint = timepoint;
			this.setup = setup;
			this.level = level;
		}

		@Override
		public Hdf5Cell< A > get( final int index )
		{
			return getGlobalIfCached( timepoint, setup, level, index );
		}

		@Override
		public Hdf5Cell< A > load( final int index, final int[] cellDims, final long[] cellMin )
		{
			return createGlobal( cellDims, cellMin, timepoint, setup, level, index );
		}
	}

	public class Hdf5BlockingCellCache implements CellCache< A >
	{
		final int timepoint;

		final int setup;

		final int level;

		public Hdf5BlockingCellCache( final int timepoint, final int setup, final int level )
		{
			this.timepoint = timepoint;
			this.setup = setup;
			this.level = level;
		}

		@Override
		public Hdf5Cell< A > get( final int index )
		{
			return getGlobalIfCachedAndLoadBlocking( timepoint, setup, level, index );
		}

		@Override
		public Hdf5Cell< A > load( final int index, final int[] cellDims, final long[] cellMin )
		{
			return createGlobalAndLoadBlocking( cellDims, cellMin, timepoint, setup, level, index );
		}
	}

	public void clearQueue()
	{
		queue.clear();
		++currentQueueFrame;
	}

	public ThreadManager getThreadManager()
	{
		return threadManager;
	}
}
