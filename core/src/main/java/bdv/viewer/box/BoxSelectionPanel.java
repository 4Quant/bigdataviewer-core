package bdv.viewer.box;

import java.util.ArrayList;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import bdv.tools.brightness.SliderPanel;
import bdv.util.BoundedInterval;
import bdv.util.ModifiableInterval;

// a JPanel containing X,Y,Z min/max sliders for adjusting an interval
public class BoxSelectionPanel extends JPanel
{
	public static interface SelectionUpdateListener
	{
		public void selectionUpdated();
	}

	private static final long serialVersionUID = 1L;

	private final BoundedInterval[] ranges;

	private final ModifiableInterval selection;

	private final ArrayList< SelectionUpdateListener > listeners;

	public BoxSelectionPanel( final ModifiableInterval selection, final Interval rangeInterval )
	{
		final int n = selection.numDimensions();
		this.selection = selection;
		ranges = new BoundedInterval[ n ];
		listeners = new ArrayList< SelectionUpdateListener >();

		setLayout( new BoxLayout( this, BoxLayout.PAGE_AXIS ) );
		for ( int d = 0; d < n; ++d )
		{
			final int rangeMin = ( int ) rangeInterval.min( d );
			final int rangeMax = ( int ) rangeInterval.max( d );
			final int initialMin = Math.max( ( int ) selection.min( d ), rangeMin );
			final int initialMax = Math.min( ( int ) selection.max( d ), rangeMax );
			final BoundedInterval range = new BoundedInterval( rangeMin, rangeMax, initialMin, initialMax, 1 )
			{
				@Override
				protected void updateInterval( final int min, final int max )
				{
					updateSelection();
				}
			};
			final JPanel sliders = new JPanel();
			sliders.setLayout( new BoxLayout( sliders, BoxLayout.PAGE_AXIS ) );
			final SliderPanel minPanel = new SliderPanel( "min", range.getMinBoundedValue(), 1 );
			minPanel.setBorder( BorderFactory.createEmptyBorder( 0, 10, 10, 10 ) );
			sliders.add( minPanel );
			final SliderPanel maxPanel = new SliderPanel( "max", range.getMaxBoundedValue(), 1 );
			maxPanel.setBorder( BorderFactory.createEmptyBorder( 0, 10, 10, 10 ) );
			sliders.add( maxPanel );
			add( sliders );
			ranges[ d ] = range;
		}
	}

	public void setBoundsInterval( final Interval interval )
	{
		final int n = selection.numDimensions();
		for ( int d = 0; d < n; ++d )
			ranges[ d ].setRange( ( int ) interval.min( d ), ( int ) interval.max( d ) );
	}

	public void addSelectionUpdateListener( final SelectionUpdateListener l )
	{
		listeners.add( l );
	}

	public void updateSelection()
	{
		final int n = selection.numDimensions();
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];
		for ( int d = 0; d < n; ++d )
		{
			min[ d ] = ranges[ d ].getMinBoundedValue().getCurrentValue();
			max[ d ] = ranges[ d ].getMaxBoundedValue().getCurrentValue();
		}
		selection.set( new FinalInterval( min, max ) );
		for ( final SelectionUpdateListener l : listeners )
			l.selectionUpdated();
	}

	public void updateSliders( final Interval interval )
	{
		final int n = selection.numDimensions();
		if ( interval.numDimensions() != n )
			throw new IllegalArgumentException();
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];
		interval.min( min );
		interval.max( max );
		for ( int d = 0; d < n; ++d )
		{
			ranges[ d ].getMinBoundedValue().setCurrentValue( ( int ) min[ d ] );
			ranges[ d ].getMaxBoundedValue().setCurrentValue( ( int ) max[ d ] );
		}
	}
}
