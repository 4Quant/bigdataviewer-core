package viewer;

import static viewer.VisibilityAndGrouping.Event.CURRENT_SOURCE_CHANGED;
import static viewer.VisibilityAndGrouping.Event.DISPLAY_MODE_CHANGED;
import static viewer.VisibilityAndGrouping.Event.GROUP_ACTIVITY_CHANGED;
import static viewer.VisibilityAndGrouping.Event.GROUP_NAME_CHANGED;
import static viewer.VisibilityAndGrouping.Event.SOURCE_ACTVITY_CHANGED;
import static viewer.VisibilityAndGrouping.Event.VISIBILITY_CHANGED;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import net.imglib2.Positionable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.ui.InteractiveDisplayCanvasComponent;
import net.imglib2.ui.OverlayRenderer;
import net.imglib2.ui.PainterThread;
import net.imglib2.ui.TransformEventHandler;
import net.imglib2.ui.TransformEventHandler3D;
import net.imglib2.ui.TransformListener;
import net.imglib2.ui.overlay.BufferedImageOverlayRenderer;
import net.imglib2.util.LinAlgHelpers;

import org.jdom2.Element;

import viewer.TextOverlayAnimator.TextPosition;
import viewer.gui.XmlIoViewerState;
import viewer.hdf5.img.Hdf5GlobalCellCache;
import viewer.render.DisplayMode;
import viewer.render.Interpolation;
import viewer.render.MultiResolutionRenderer;
import viewer.render.Source;
import viewer.render.SourceAndConverter;
import viewer.render.SourceGroup;
import viewer.render.SourceState;
import viewer.render.ViewerState;
import viewer.render.overlay.MultiBoxOverlayRenderer;
import viewer.render.overlay.SourceInfoOverlayRenderer;
import viewer.util.AbstractTransformAnimator;
import viewer.util.Affine3DHelpers;


/**
 * A JPanel for viewing multiple of {@link Source}s.
 * The panel contains a {@link InteractiveDisplayCanvasComponent canvas} and a time slider (if there are multiple time-points).
 * TODO
 * TODO
 * TODO
 * TODO
 * TODO
 * TODO
 * TODO
 * TODO
 * TODO
 * TODO
 *
 * @author Tobias Pietzsch <tobias.pietzsch@gmail.com>
 */
public class ViewerPanel extends JPanel implements OverlayRenderer, TransformListener< AffineTransform3D >, PainterThread.Paintable, VisibilityAndGrouping.UpdateListener
{
	/**
	 * TODO
	 */
	protected ViewerState state;

	/**
	 * TODO
	 */
	protected MultiResolutionRenderer imageRenderer;

	// TODO: move to specialized class
	protected MultiBoxOverlayRenderer multiBoxOverlayRenderer;

	// TODO: move to specialized class
	protected SourceInfoOverlayRenderer sourceInfoOverlayRenderer;

	/**
	 * Transformation set by the interactive viewer.
	 */
	final protected AffineTransform3D viewerTransform;

	/**
	 * Canvas used for displaying the rendered {@link #screenImages screen
	 * image}.
	 */
	final protected InteractiveDisplayCanvasComponent< AffineTransform3D > display;

	final protected JSlider sliderTime;

	/**
	 * Thread that triggers repainting of the display.
	 */
	final protected PainterThread painterThread;

	/**
	 * Keeps track of the current mouse coordinates, which are used to provide
	 * the current global position (see {@link #getGlobalMouseCoordinates(RealPositionable)}).
	 */
	final protected MouseCoordinateListener mouseCoordinates;

	/**
	 * TODO
	 */
	final protected VisibilityAndGrouping visibilityAndGrouping;

	/**
	 * These listeners will be notified about changes to the
	 * {@link #viewerTransform}. This is done <em>before</em> calling
	 * {@link #requestRepaint()} so listeners have the chance to interfere.
	 */
	protected final CopyOnWriteArrayList< TransformListener< AffineTransform3D > > transformListeners;

	/**
	 * TODO
	 */
	protected AbstractTransformAnimator currentAnimator = null;

	/**
	 * TODO
	 */
	protected TextOverlayAnimator animatedOverlay;

	/**
	 * TODO
	 */
	protected final MessageOverlayAnimator msgOverlay;

	/**
	 *
	 * @param sources
	 *            the {@link SourceAndConverter sources} to display.
	 * @param numTimePoints
	 *            number of available timepoints.
	 * @param numMipmapLevels
	 *            number of available mipmap levels.
	 */
	public ViewerPanel( final List< SourceAndConverter< ? > > sources, final int numTimePoints, final Hdf5GlobalCellCache< ? > cache )
	{
		super( new BorderLayout(), false );
		this.cache = cache;

		// TODO: should be settable from the outside with fluent API Options object?
		final int defaultWidth = 800;
		final int defaultHeight = 600;

		final int numGroups = 10;
		final ArrayList< SourceGroup > groups = new ArrayList< SourceGroup >( numGroups );
		for ( int i = 0; i < numGroups; ++i )
		{
			final SourceGroup g = new SourceGroup( "group " + Integer.toString( i + 1 ) );
			if ( i < sources.size() )
			{
				g.addSource( i );
			}
			groups.add( g );
		}

		state = new ViewerState( sources, groups, numTimePoints );
		if ( !sources.isEmpty() )
			state.setCurrentSource( 0 );
		multiBoxOverlayRenderer = new MultiBoxOverlayRenderer();
		sourceInfoOverlayRenderer = new SourceInfoOverlayRenderer();

		painterThread = new PainterThread( this );
		cache.getThreadManager().addConsumer( painterThread );
		viewerTransform = new AffineTransform3D();
		display = new InteractiveDisplayCanvasComponent< AffineTransform3D >( defaultWidth, defaultHeight, TransformEventHandler3D.factory() );
		display.addTransformListener( this );
		final BufferedImageOverlayRenderer renderTarget = new BufferedImageOverlayRenderer();
		renderTarget.setCanvasSize( defaultWidth, defaultHeight );
		display.addOverlayRenderer( renderTarget );
		display.addOverlayRenderer( this );

		// TODO: should be settable from the outside with fluent API Options object
		final double[] screenScales = new double[] { 1, 0.75, 0.5, 0.25, 0.125 };
		final long targetRenderNanos = 30 * 1000000;
		final boolean doubleBuffered = true;
		final int numRenderingThreads = 5;
		imageRenderer = new MultiResolutionRenderer( renderTarget, painterThread, screenScales, targetRenderNanos, doubleBuffered, numRenderingThreads, cache );

		mouseCoordinates = new MouseCoordinateListener();
		display.addHandler( mouseCoordinates );

		add( display, BorderLayout.CENTER );
		if ( numTimePoints > 1 )
		{
			sliderTime = new JSlider( JSlider.HORIZONTAL, 0, numTimePoints - 1, 0 );
			sliderTime.addChangeListener( new ChangeListener()
			{
				@Override
				public void stateChanged( final ChangeEvent e )
				{
					if ( e.getSource().equals( sliderTime ) )
						updateTimepoint( sliderTime.getValue() );
				}
			} );
			add( sliderTime, BorderLayout.SOUTH );
		}
		else
			sliderTime = null;

		visibilityAndGrouping = new VisibilityAndGrouping( state );
		visibilityAndGrouping.addUpdateListener( this );

		transformListeners = new CopyOnWriteArrayList< TransformListener< AffineTransform3D > >();

		msgOverlay = new MessageOverlayAnimator( 800 );
		animatedOverlay = new TextOverlayAnimator( "Press <F1> for help.", 3000, TextPosition.CENTER );

		painterThread.start();
	}

	// TODO: remove?
	public void addHandler( final Object handler )
	{
		display.addHandler( handler );
	}

	/**
	 * Set {@code gPos} to the current mouse coordinates transformed into the
	 * global coordinate system.
	 *
	 * @param gPos
	 *            is set to the current global coordinates.
	 */
	public void getGlobalMouseCoordinates( final RealPositionable gPos )
	{
		assert gPos.numDimensions() == 3;
		final RealPoint lPos = new RealPoint( 3 );
		mouseCoordinates.getMouseCoordinates( lPos );
		viewerTransform.applyInverse( gPos, lPos );
	}

	@Override
	public void paint()
	{
		imageRenderer.paint( state );

		synchronized ( this )
		{
			if ( currentAnimator != null )
			{
				final TransformEventHandler< AffineTransform3D > handler = display.getTransformEventHandler();
				final AffineTransform3D transform = currentAnimator.getCurrent( System.currentTimeMillis() );
				handler.setTransform( transform );
				transformChanged( transform );
				if ( currentAnimator.isComplete() )
					currentAnimator = null;
			}
		}

		display.repaint();
	}

	/**
	 * Repaint as soon as possible.
	 */
	public void requestRepaint()
	{
		imageRenderer.requestRepaint();
	}

	@Override
	public void drawOverlays( final Graphics g )
	{
		multiBoxOverlayRenderer.setViewerState( state );
		multiBoxOverlayRenderer.updateVirtualScreenSize( display.getWidth(), display.getHeight() );
		multiBoxOverlayRenderer.paint( ( Graphics2D ) g );

		sourceInfoOverlayRenderer.setViewerState( state );
		sourceInfoOverlayRenderer.paint( ( Graphics2D ) g );

		final RealPoint gPos = new RealPoint( 3 );
		getGlobalMouseCoordinates( gPos );
		final String mousePosGlobalString = String.format( "(%6.1f,%6.1f,%6.1f)", gPos.getDoublePosition( 0 ), gPos.getDoublePosition( 1 ), gPos.getDoublePosition( 2 ) );

		g.setFont( new Font( "Monospaced", Font.PLAIN, 12 ) );
		g.drawString( mousePosGlobalString, ( int ) g.getClipBounds().getWidth() - 170, 25 );

		if ( animatedOverlay != null )
		{
			animatedOverlay.paint( ( Graphics2D ) g, System.currentTimeMillis() );
			if ( animatedOverlay.isComplete() )
				animatedOverlay = null;
			else
				display.repaint();
		}

		if ( !msgOverlay.isComplete() )
		{
			msgOverlay.paint( ( Graphics2D ) g, System.currentTimeMillis() );
			if ( !msgOverlay.isComplete() )
				display.repaint();
		}

		if ( multiBoxOverlayRenderer.isHighlightInProgress() )
			display.repaint();
	}

	@Override
	public synchronized void transformChanged( final AffineTransform3D transform )
	{
		viewerTransform.set( transform );
		state.setViewerTransform( transform );
		for ( final TransformListener< AffineTransform3D > l : transformListeners )
			l.transformChanged( viewerTransform );
		requestRepaint();
	}

	@Override
	public void visibilityChanged( final VisibilityAndGrouping.Event e )
	{
		switch ( e.id )
		{
		case CURRENT_SOURCE_CHANGED:
			multiBoxOverlayRenderer.highlight( visibilityAndGrouping.getCurrentSource() );
			display.repaint();
			break;
		case DISPLAY_MODE_CHANGED:
			showMessage( visibilityAndGrouping.getDisplayMode().getName() );
			display.repaint();
			break;
		case GROUP_NAME_CHANGED:
			display.repaint();
			break;
		case SOURCE_ACTVITY_CHANGED:
			// TODO multiBoxOverlayRenderer.highlight() all sources that became visible
			break;
		case GROUP_ACTIVITY_CHANGED:
			// TODO multiBoxOverlayRenderer.highlight() all sources that became visible
			break;
		case VISIBILITY_CHANGED:
			requestRepaint();
			break;
		}
	}

	private final static double c = Math.cos( Math.PI / 4 );

	/**
	 * The planes which can be aligned with the viewer coordinate system: XY,
	 * ZY, and XZ plane.
	 */
	protected static enum AlignPlane
	{
		XY( "XY", 2, new double[] { 1, 0, 0, 0 } ),
		ZY( "ZY", 0, new double[] { c, 0, -c, 0 } ),
		XZ( "XZ", 1, new double[] { c, c, 0, 0 } );

		private final String name;

		public String getName()
		{
			return name;
		}

		/**
		 * rotation from the xy-plane aligned coordinate system to this plane.
		 */
		private final double[] qAlign;

		/**
		 * Axis index. The plane spanned by the remaining two axes will be
		 * transformed to the same plane by the computed rotation and the
		 * "rotation part" of the affine source transform.
		 * @see Affine3DHelpers#extractApproximateRotationAffine(AffineTransform3D, double[], int)
		 */
		private final int coerceAffineDimension;

		private AlignPlane( final String name, final int coerceAffineDimension, final double[] qAlign )
		{
			this.name = name;
			this.coerceAffineDimension = coerceAffineDimension;
			this.qAlign = qAlign;
		}
	}

	/**
	 * TODO
	 * @param plane
	 */
	// TODO: public?
	protected synchronized void align( final AlignPlane plane )
	{
		final SourceState< ? > source = state.getSources().get( state.getCurrentSource() );
		final AffineTransform3D sourceTransform = source.getSpimSource().getSourceTransform( state.getCurrentTimepoint(), 0 );

		final double[] qSource = new double[ 4 ];
		Affine3DHelpers.extractRotationAnisotropic( sourceTransform, qSource );

		final double[] qTmpSource = new double[ 4 ];
		Affine3DHelpers.extractApproximateRotationAffine( sourceTransform, qSource, plane.coerceAffineDimension );
		LinAlgHelpers.quaternionMultiply( qSource, plane.qAlign, qTmpSource );

		final double[] qTarget = new double[ 4 ];
		LinAlgHelpers.quaternionInvert( qTmpSource, qTarget );

		final AffineTransform3D transform = display.getTransformEventHandler().getTransform();
		currentAnimator = new RotationAnimator( transform, mouseCoordinates.getX(), mouseCoordinates.getY(), qTarget, 300 );
		currentAnimator.setTime( System.currentTimeMillis() );
		transformChanged( transform );
	}

	/**
	 * TODO
	 * @param timepoint
	 */
	// TODO: public?
	protected synchronized void updateTimepoint( final int timepoint )
	{
		if ( state.getCurrentTimepoint() != timepoint )
		{
			state.setCurrentTimepoint( timepoint );
			requestRepaint();
		}
	}

	/**
	 * TODO
	 */
	// TODO: public?
	protected synchronized void toggleInterpolation()
	{
		final Interpolation interpolation = state.getInterpolation();
		if ( interpolation == Interpolation.NEARESTNEIGHBOR )
		{
			state.setInterpolation( Interpolation.NLINEAR );
			showMessage( "tri-linear interpolation" );
		}
		else
		{
			state.setInterpolation( Interpolation.NEARESTNEIGHBOR );
			showMessage( "nearest-neighbor interpolation" );
		}
		requestRepaint();
	}

	/**
	 * TODO
	 * @param index
	 */
	// TODO: public?
	protected void setCurrentGroupOrSource( final int index )
	{
		if ( visibilityAndGrouping.isGroupingEnabled() )
			visibilityAndGrouping.setCurrentGroup( index );
		else
			visibilityAndGrouping.setCurrentSource( index );
	}

	/**
	 * TODO
	 * @param index
	 */
	// TODO: public?
	protected void toggleActiveGroupOrSource( final int index )
	{
		if ( visibilityAndGrouping.isGroupingEnabled() )
			visibilityAndGrouping.setGroupActive( index, !visibilityAndGrouping.isGroupActive( index ) );
		else
			visibilityAndGrouping.setSourceActive( index, !visibilityAndGrouping.isSourceActive( index ) );
	}

	/**
	 * TODO
	 */
	public synchronized void setDisplayMode( final DisplayMode displayMode )
	{
		visibilityAndGrouping.setDisplayMode( displayMode );
	}

	/**
	 * Set the index of the source to display.
	 */
	public synchronized void setCurrentSource( final int sourceIndex )
	{
		visibilityAndGrouping.setCurrentSource( sourceIndex );
	}

	/**
	 * Set the viewer transform.
	 */
	public synchronized void setCurrentViewerTransform( final AffineTransform3D viewerTransform )
	{
		display.getTransformEventHandler().setTransform( viewerTransform );
		transformChanged( viewerTransform );
	}

	/**
	 * Get a copy of the current {@link ViewerState}.
	 *
	 * @return a copy of the current {@link ViewerState}.
	 */
	public synchronized ViewerState getState()
	{
		return state.copy();
	}

	/**
	 * Get the viewer canvas.
	 *
	 * @return the viewer canvas.
	 */
	public InteractiveDisplayCanvasComponent< AffineTransform3D > getDisplay()
	{
		return display;
	}

	/**
	 * Display the specified message in a text overlay for a short time.
	 *
	 * @param msg
	 *            String to display. Should be just one line of text.
	 */
	public void showMessage( final String msg )
	{
		msgOverlay.add( msg );
		display.repaint();
	}

	/**
	 * Add a {@link TransformListener} to notify about viewer transformation
	 * changes. Listeners will be notified <em>before</em> calling
	 * {@link #requestRepaint()} so they have the chance to interfere.
	 *
	 * @param listener
	 *            the transform listener to add.
	 */
	public synchronized void addTransformListener( final TransformListener< AffineTransform3D > listener )
	{
		addTransformListener( listener, Integer.MAX_VALUE );
	}

	/**
	 * Add a {@link TransformListener} to notify about viewer transformation
	 * changes. Listeners will be notified <em>before</em> calling
	 * {@link #requestRepaint()} so they have the chance to interfere.
	 *
	 * @param listener
	 *            the transform listener to add.
	 * @param index
	 *            position in the list of listeners at which to insert this one.
	 */
	public void addTransformListener( final TransformListener< AffineTransform3D > listener, final int index )
	{
		synchronized ( transformListeners )
		{
			final int s = transformListeners.size();
			transformListeners.add( index < 0 ? 0 : index > s ? s : index, listener );
		}
	}

	/**
	 * Remove a {@link TransformListener}.
	 *
	 * @param listener
	 *            the transform listener to remove.
	 */
	public synchronized void removeTransformListener( final TransformListener< AffineTransform3D > listener )
	{
		synchronized ( transformListeners )
		{
			transformListeners.remove( listener );
		}
	}

	protected class MouseCoordinateListener implements MouseMotionListener
	{
		private int x;

		private int y;

		public synchronized void getMouseCoordinates( final Positionable p )
		{
			p.setPosition( x, 0 );
			p.setPosition( y, 1 );
		}

		@Override
		public synchronized void mouseDragged( final MouseEvent e )
		{
			x = e.getX();
			y = e.getY();
		}

		@Override
		public synchronized void mouseMoved( final MouseEvent e )
		{
			x = e.getX();
			y = e.getY();
			display.repaint(); // TODO: only when overlays are visible
		}

		public synchronized int getX()
		{
			return x;
		}

		public synchronized int getY()
		{
			return y;
		}
	}

	public synchronized Element stateToXml()
	{
		return new XmlIoViewerState().toXml( state );
	}

	public synchronized void stateFromXml( final Element parent )
	{
		final XmlIoViewerState io = new XmlIoViewerState();
		io.restoreFromXml( parent.getChild( io.getTagName() ), state );
	}

	@Override
	public void setCanvasSize( final int width, final int height )
	{}

	public VisibilityAndGrouping getVisibilityAndGrouping()
	{
		return visibilityAndGrouping;
	}

	// TODO: this is a quick hack. Should it stay like this?
	final private Hdf5GlobalCellCache< ? > cache;
	public void stop()
	{
		painterThread.interrupt();
		cache.getThreadManager().removeConsumer( painterThread );
	}
}