package viewer.hdf5.img;

import java.util.concurrent.ConcurrentHashMap;

import net.imglib2.ui.util.StopWatch;

public class CacheIoTiming
{
	public static class IoTimeBudget
	{
		private final long[] budget;

		public IoTimeBudget( final long[] budget )
		{
			this.budget = budget.clone();
			for ( int l = 1; l < budget.length; ++l )
				if ( budget[ l ] > budget[ l - 1 ] )
					budget[ l ] = budget[ l - 1 ];
		}

		public synchronized long timeLeft( final int level )
		{
			return budget[ level ];
		}

		public synchronized void use( final long t, final int level )
		{
			int l = 0;
			for ( ; l <= level; ++l )
				budget[ l ] -= t;
			for ( ; l < budget.length && budget[ l ] > budget[ l - 1 ]; ++l )
				budget[ l ] = budget[ l - 1 ];
		}
	}

	public static class IoStatistics
	{
		private final ConcurrentHashMap< Thread, StopWatch > perThreadStopWatches = new ConcurrentHashMap< Thread, StopWatch >();

		private final StopWatch stopWatch;

		private int numRunningThreads;

		private long ioBytes;

		private IoTimeBudget ioTimeBudget;

		public IoStatistics()
		{
			stopWatch = new StopWatch();
			ioBytes = 0;
			numRunningThreads = 0;
			ioTimeBudget = null;
		}

		public synchronized void start()
		{
			getThreadStopWatch().start();
			if( numRunningThreads++ == 0 )
				stopWatch.start();
		}

		public synchronized void stop()
		{
			getThreadStopWatch().stop();
			if( --numRunningThreads == 0 )
				stopWatch.stop();
		}

		public void incIoBytes( final long n )
		{
			ioBytes += n;
		}

		public long getIoBytes()
		{
			return ioBytes;
		}

		public long getIoNanoTime()
		{
			return stopWatch.nanoTime();
		}

		public long getCumulativeIoNanoTime()
		{
			long sum = 0;
			for ( final StopWatch w : perThreadStopWatches.values() )
				sum += w.nanoTime();
			return sum;
		}

		public IoTimeBudget getIoTimeBudget()
		{
			return ioTimeBudget;
		}

		public void setIoTimeBudget( final IoTimeBudget budget )
		{
			ioTimeBudget = budget;
		}

		private StopWatch getThreadStopWatch()
		{
			final Thread thread = Thread.currentThread();
			StopWatch w = perThreadStopWatches.get( thread );
			if ( w == null )
			{
				w = new StopWatch();
				perThreadStopWatches.put( thread, w );
			}
			return w;
		}
	}

	private final static ConcurrentHashMap< ThreadGroup, IoStatistics > perThreadGroupIoStatistics = new ConcurrentHashMap< ThreadGroup, IoStatistics >();

	public static IoStatistics getThreadGroupIoStatistics()
	{
		final ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
		IoStatistics statistics = perThreadGroupIoStatistics.get( threadGroup );
		if ( statistics == null )
		{
			statistics = new IoStatistics();
			perThreadGroupIoStatistics.put( threadGroup, statistics );
		}
		return statistics;
	}

	public static long getThreadGroupIoNanoTime()
	{
		return getThreadGroupIoStatistics().getIoNanoTime();
	}

	public static long getThreadGroupIoBytes()
	{
		return getThreadGroupIoStatistics().getIoBytes();
	}
}
