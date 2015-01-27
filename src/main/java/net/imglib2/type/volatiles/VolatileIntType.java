package net.imglib2.type.volatiles;

import net.imglib2.img.NativeImg;
import net.imglib2.img.NativeImgFactory;
import net.imglib2.img.basictypeaccess.IntAccess;
import net.imglib2.img.basictypeaccess.ShortAccess;
import net.imglib2.img.basictypeaccess.volatiles.VolatileIntAccess;
import net.imglib2.img.basictypeaccess.volatiles.VolatileShortAccess;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileIntArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * A {@link net.imglib2.Volatile} variant of {@link net.imglib2.type.numeric.integer.UnsignedShortType}. It uses an
 * underlying {@link net.imglib2.type.numeric.integer.UnsignedShortType} that maps into a
 * {@link net.imglib2.img.basictypeaccess.volatiles.VolatileShortAccess}.
 *
 * @author Tobias Pietzsch <tobias.pietzsch@gmail.com>
 */
public class VolatileIntType extends AbstractVolatileNativeRealType<IntType, VolatileIntType>
{
	final protected NativeImg< ?, ? extends VolatileIntAccess> img;

	private static class WrappedIntType extends IntType
	{
		public WrappedIntType( final NativeImg<?, ? extends IntAccess> img )
		{
			super( img );
		}

		public WrappedIntType( final IntAccess access )
		{
			super( access );
		}

		public void setAccess( final IntAccess access )
		{
			dataAccess = access;
		}
	}

	// this is the constructor if you want it to read from an array
	public VolatileIntType(final NativeImg<?, ? extends VolatileIntAccess> img)
	{
		super( new WrappedIntType( img ), false );
		this.img = img;
	}

	// this is the constructor if you want to specify the dataAccess
	public VolatileIntType(final VolatileIntAccess access)
	{
		super( new WrappedIntType( access ), access.isValid() );
		this.img = null;
	}

	// this is the constructor if you want it to be a variable
	public VolatileIntType(final int value)
	{
		this( new VolatileIntArray( 1, true ) );
		set( value );
	}

	// this is the constructor if you want it to be a variable
	public VolatileIntType()
	{
		this( 0 );
	}

	public void set( final int value )
	{
		get().set( value );
	}

	@Override
	public void updateContainer( final Object c )
	{
		final VolatileIntAccess a = img.update( c );
		( (WrappedIntType) t ).setAccess( a );
		setValid( a.isValid() );
	}

	@Override
	public NativeImg<VolatileIntType, ? extends VolatileShortAccess > createSuitableNativeImg( final NativeImgFactory<VolatileIntType> storageFactory, final long[] dim )
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public VolatileIntType duplicateTypeOnSameNativeImg()
	{
		return new VolatileIntType( img );
	}

	@Override
	public VolatileIntType createVariable()
	{
		return new VolatileIntType();
	}

	@Override
	public VolatileIntType copy()
	{
		final VolatileIntType v = createVariable();
		v.set( this );
		return v;
	}
}
