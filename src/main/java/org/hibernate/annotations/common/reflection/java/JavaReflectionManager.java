/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.annotations.common.reflection.java;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.hibernate.annotations.common.Version;
import org.hibernate.annotations.common.reflection.AnnotationReader;
import org.hibernate.annotations.common.reflection.MetadataProvider;
import org.hibernate.annotations.common.reflection.MetadataProviderInjector;
import org.hibernate.annotations.common.reflection.ReflectionManager;
import org.hibernate.annotations.common.reflection.XClass;
import org.hibernate.annotations.common.reflection.XMethod;
import org.hibernate.annotations.common.reflection.XPackage;
import org.hibernate.annotations.common.reflection.XProperty;
import org.hibernate.annotations.common.reflection.java.generics.IdentityTypeEnvironment;
import org.hibernate.annotations.common.reflection.java.generics.TypeEnvironment;
import org.hibernate.annotations.common.reflection.java.generics.TypeEnvironmentFactory;
import org.hibernate.annotations.common.reflection.java.generics.TypeSwitch;
import org.hibernate.annotations.common.reflection.java.generics.TypeUtils;
import org.hibernate.annotations.common.util.impl.LoggerFactory;

/**
 * The factory for all the objects in this package.
 *
 * @author Paolo Perrotta
 * @author Davide Marchignoli
 * @author Emmanuel Bernard
 * @author Sanne Grinovero
 */
public class JavaReflectionManager implements ReflectionManager, MetadataProviderInjector {

	private static final boolean METADATA_CACHE_DIAGNOSTICS = Boolean.getBoolean( "org.hibernate.annotations.common.METADATA_CACHE_DIAGNOSTICS" );

	private MetadataProvider metadataProvider;

	private final AtomicBoolean empty = new AtomicBoolean(true);

	static {
		LoggerFactory.make( Version.class.getName() ).version( Version.getVersionString() );
	}

	public MetadataProvider getMetadataProvider() {
		if (metadataProvider == null) {
			setMetadataProvider( new JavaMetadataProvider() );
		}
		return metadataProvider;
	}

	public void setMetadataProvider(MetadataProvider metadataProvider) {
		this.metadataProvider = metadataProvider;
	}

	private static class TypeKey extends Pair<Type, TypeEnvironment> {
		TypeKey(Type t, TypeEnvironment context) {
			super( t, context );
		}
	}

	private static class MemberKey extends Pair<Member, TypeEnvironment> {
		MemberKey(Member member, TypeEnvironment context) {
			super( member, context );
		}
	}

	private final Map<TypeKey, JavaXClass> xClasses = new HashMap<TypeKey, JavaXClass>();

	private final Map<Package, JavaXPackage> packagesToXPackages = new HashMap<Package, JavaXPackage>();

	private final Map<MemberKey, JavaXProperty> xProperties = new HashMap<MemberKey, JavaXProperty>();

	private final Map<MemberKey, JavaXMethod> xMethods = new HashMap<MemberKey, JavaXMethod>();

	public XClass toXClass(Class clazz) {
		return toXClass( clazz, IdentityTypeEnvironment.INSTANCE );
	}

	public Class toClass(XClass xClazz) {
		if ( ! ( xClazz instanceof JavaXClass ) ) {
			throw new IllegalArgumentException( "XClass not coming from this ReflectionManager implementation" );
		}
		return (Class) ( (JavaXClass) xClazz ).toAnnotatedElement();
	}

	public Method toMethod(XMethod xMethod) {
		if ( ! ( xMethod instanceof JavaXMethod ) ) {
			throw new IllegalArgumentException( "XMethod not coming from this ReflectionManager implementation" );
		}
		return (Method) ( (JavaXAnnotatedElement) xMethod ).toAnnotatedElement();
	}

	XClass toXClass(Type t, final TypeEnvironment context) {
		return new TypeSwitch<XClass>() {
			@Override
			public XClass caseClass(Class classType) {
				TypeKey key = new TypeKey( classType, context );
				JavaXClass result = xClasses.get( key );
				if ( result == null ) {
					result = new JavaXClass( classType, context, JavaReflectionManager.this );
					used();
					xClasses.put( key, result );
				}
				return result;
			}

			@Override
			public XClass caseParameterizedType(ParameterizedType parameterizedType) {
				return toXClass( parameterizedType.getRawType(),
					TypeEnvironmentFactory.getEnvironment( parameterizedType, context )
				);
			}
		}.doSwitch( context.bind( t ) );
	}

	@Override
	public XPackage toXPackage(Package pkg) {
		JavaXPackage xPackage = packagesToXPackages.get( pkg );
		if ( xPackage == null ) {
			xPackage = new JavaXPackage( pkg, this );
			used();
			packagesToXPackages.put( pkg, xPackage );
		}
		return xPackage;
	}

	XProperty getXProperty(Member member, TypeEnvironment context) {
		MemberKey key = new MemberKey( member, context );
        //FIXME get is as expensive as create most time spent in hashCode and equals
        JavaXProperty xProperty = xProperties.get( key );
		if ( xProperty == null ) {
			xProperty = JavaXProperty.create( member, context, this );
			used();
			xProperties.put( key, xProperty );
		}
		return xProperty;
	}

	XMethod getXMethod(Member member, TypeEnvironment context) {
		MemberKey key = new MemberKey( member, context );
        //FIXME get is as expensive as create most time spent in hashCode and equals
        JavaXMethod xMethod = xMethods.get( key );
		if ( xMethod == null ) {
			xMethod = JavaXMethod.create( member, context, this );
			used();
			xMethods.put( key, xMethod );
		}
		return xMethod;
	}

	TypeEnvironment getTypeEnvironment(final Type t) {
		return new TypeSwitch<TypeEnvironment>() {
			@Override
			public TypeEnvironment caseClass(Class classType) {
				return TypeEnvironmentFactory.getEnvironment( classType );
			}

			@Override
			public TypeEnvironment caseParameterizedType(ParameterizedType parameterizedType) {
				return TypeEnvironmentFactory.getEnvironment( parameterizedType );
			}

			@Override
			public TypeEnvironment defaultCase(Type type) {
				return IdentityTypeEnvironment.INSTANCE;
			}
		}.doSwitch( t );
	}

	public JavaXType toXType(TypeEnvironment context, Type propType) {
		Type boundType = toApproximatingEnvironment( context ).bind( propType );
		if ( TypeUtils.isArray( boundType ) ) {
			return new JavaXArrayType( propType, context, this );
		}
		if ( TypeUtils.isCollection( boundType ) ) {
			return new JavaXCollectionType( propType, context, this );
		}
		if ( TypeUtils.isSimple( boundType ) ) {
			return new JavaXSimpleType( propType, context, this );
		}
		throw new IllegalArgumentException( "No PropertyTypeExtractor available for type void " );
	}

	public boolean equals(XClass class1, Class class2) {
		if ( class1 == null ) {
			return class2 == null;
		}
		return ( (JavaXClass) class1 ).toClass().equals( class2 );
	}

	public TypeEnvironment toApproximatingEnvironment(TypeEnvironment context) {
		return TypeEnvironmentFactory.toApproximatingEnvironment( context );
	}

    public AnnotationReader buildAnnotationReader(AnnotatedElement annotatedElement) {
        return getMetadataProvider().getAnnotationReader( annotatedElement );
    }
    
    public Map getDefaults() {
        return getMetadataProvider().getDefaults();
    }

	@Override
	public void reset() {
		boolean wasEmpty = empty.getAndSet( true );
		if ( !wasEmpty ) {
			this.xClasses.clear();
			this.packagesToXPackages.clear();
			this.xProperties.clear();
			this.xMethods.clear();
			if ( METADATA_CACHE_DIAGNOSTICS ) {
				new RuntimeException( "Diagnostics message : Caches now empty" ).printStackTrace();
			}
		}
		if ( metadataProvider != null ) {
			this.metadataProvider.reset();
		}
	}

	private void used() {
		boolean wasEmpty = empty.getAndSet( false );
		if ( wasEmpty && METADATA_CACHE_DIAGNOSTICS ) {
			new RuntimeException( "Diagnostics message : Caches now being used" ).printStackTrace();
		}
	}

}
