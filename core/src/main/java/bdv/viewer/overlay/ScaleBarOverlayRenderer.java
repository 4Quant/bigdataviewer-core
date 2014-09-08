package bdv.viewer.overlay;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.text.DecimalFormat;
import java.util.List;

import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.realtransform.AffineTransform3D;
import bdv.util.Affine3DHelpers;
import bdv.viewer.Source;
import bdv.viewer.state.SourceState;
import bdv.viewer.state.ViewerState;

public class ScaleBarOverlayRenderer
{
	private final Font font = new Font( "SansSerif", Font.PLAIN, 12 );

	/**
	 * Try to keep the scale bar as close to this length (in pixels) as possible.
	 */
	private final int targetScaleBarLength = 100;

	/**
	 * For finding the value to display on the scalebar: into how many parts is
	 * each power of ten divided? For example, 4 means the following are
	 * possible values:
	 * <em>..., 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10, ...</em>
	 */
	private final int subdivPerPowerOfTen = 4;

	private double scaleBarLength;

	private double scale;

	private String unit;

	public synchronized void paint( final Graphics2D g )
	{
		final DecimalFormat format = new DecimalFormat("0.####");
		final String scaleBarText = format.format( scale ) + " " + unit;

		// draw scalebar
		final int x = 20;
		final int y = ( int ) g.getClipBounds().getHeight() - 30;
		g.fillRect( x, y, ( int ) scaleBarLength, 10 );

		// draw label
		final FontRenderContext frc = g.getFontRenderContext();
		final TextLayout layout = new TextLayout( scaleBarText, font, frc );
		final Rectangle2D bounds = layout.getBounds();
		final float tx = ( float ) ( 20 + ( scaleBarLength - bounds.getMaxX() ) / 2 );
		final float ty = y - 5;
		layout.draw( g, tx, ty );
	}

	private static final String[] lengthUnits = { "nm", "µm", "mm", "m", "km" };

	/**
	 * Update data to show in the overlay.
	 */
	public synchronized void setViewerState( final ViewerState state )
	{
		synchronized ( state )
		{
			final List< SourceState< ? > > sources = state.getSources();
			if ( ! sources.isEmpty() )
			{
				final Source< ? > spimSource = sources.get( state.getCurrentSource() ).getSpimSource();
				final VoxelDimensions voxelDimensions = spimSource.getVoxelDimensions();
				if ( voxelDimensions == null )
					return;

				final AffineTransform3D transform = new AffineTransform3D();
				state.getViewerTransform( transform );

				final int t = state.getCurrentTimepoint();
				transform.concatenate( spimSource.getSourceTransform( t, 0 ) );
				final double sizeOfOnePixel = voxelDimensions.dimension( 0 ) / Affine3DHelpers.extractScale( transform, 0 );

				// find good scaleBarLength and corresponding scale value
				final double sT = targetScaleBarLength * sizeOfOnePixel;
				final double pot = Math.floor( Math.log10( sT ) );
				final double l2 =  sT / Math.pow( 10, pot );
				final int fracs = ( int ) ( 0.1 * l2 * subdivPerPowerOfTen );
				final double scale1 = ( fracs > 0 ) ? Math.pow( 10, pot + 1 ) * fracs / subdivPerPowerOfTen : Math.pow( 10, pot );
				final double scale2 = ( fracs == 3 ) ? Math.pow( 10, pot + 1 ) : Math.pow( 10, pot + 1 ) * ( fracs + 1 ) / subdivPerPowerOfTen;

				final double lB1 = scale1 / sizeOfOnePixel;
				final double lB2 = scale2 / sizeOfOnePixel;

				if ( Math.abs( lB1 - targetScaleBarLength ) < Math.abs( lB2 - targetScaleBarLength ) )
				{
					scale = scale1;
					scaleBarLength = lB1;
				}
				else
				{
					scale = scale2;
					scaleBarLength = lB2;
				}

				// If unit is a known unit (such as nm) then try to modify scale
				// and unit such that the displayed string is short.
				// For example, replace "0.021 µm" by "21 nm".
				String scaleUnit = voxelDimensions.unit();
				if ( "um".equals( scaleUnit ) )
					scaleUnit = "µm";
				int scaleUnitIndex = -1;
				for ( int i = 0; i < lengthUnits.length; ++i )
					if ( lengthUnits[ i ].equals( scaleUnit ) )
					{
						scaleUnitIndex = i;
						break;
					}
				if ( scaleUnitIndex >= 0 )
				{
					int shifts = ( int ) Math.floor( ( Math.log10( scale ) + 1 ) / 3 );
					int shiftedIndex = scaleUnitIndex + shifts;
					if ( shiftedIndex < 0 )
					{
						shifts = -scaleUnitIndex;
						shiftedIndex = 0;
					}
					else if ( shiftedIndex >= lengthUnits.length )
					{
						shifts = lengthUnits.length - 1 - scaleUnitIndex;
						shiftedIndex = lengthUnits.length - 1;
					}

					scale = scale / Math.pow( 1000, shifts );
					unit = lengthUnits[ shiftedIndex ];
				}
				else
				{
					unit = scaleUnit;
				}
			}
		}
	}
}
