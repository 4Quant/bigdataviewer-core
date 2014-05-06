package bdv;

import mpicbg.spim.data.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.NumericType;

public class SpimSource< T extends NumericType< T > > extends AbstractSpimSource< T >
{
	protected final ViewerImgLoader< T, ? > imgLoader;

	@SuppressWarnings( "unchecked" )
	public SpimSource( final SequenceViewsLoader loader, final int setup, final String name )
	{
		super( loader, setup, name );
		final SequenceDescription seq = loader.getSequenceDescription();
		imgLoader = ( ViewerImgLoader< T, ? > ) seq.getImgLoader();
		loadTimepoint( 0 );
	}

	@Override
	public T getType()
	{
		return imgLoader.getImageType();
	}

	@Override
	protected RandomAccessibleInterval< T > getImage( final ViewId viewId, final int level )
	{
		return imgLoader.getImage( viewId, level );
	}

	@Override
	protected AffineTransform3D[] getMipmapTransforms()
	{
		return imgLoader.getMipmapTransforms( setupId );
	}
}
