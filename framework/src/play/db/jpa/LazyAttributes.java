package play.db.jpa;

import java.beans.Introspector;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import jakarta.persistence.Basic;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import org.hibernate.annotations.LazyGroup;

/**
 * PF-56: the entity attributes that are declared lazy and that Hibernate loads eagerly, because Play does not
 * enhance entities with Hibernate's bytecode enhancer. Hibernate 7 makes its load-time transformer only when the
 * {@code PersistenceUnitInfo} gives it a temporary class loader, which Play's does not, and Play has loaded the
 * entity classes before the factory is built anyway; Hibernate's build-time enhancer never reaches the classes Play
 * compiles and defines itself. Without enhancement Hibernate loads these eagerly, and logs nothing:
 * <ul>
 * <li>{@code @Basic(fetch = LAZY)}, and Hibernate's {@code @LazyGroup};</li>
 * <li>the inverse side of a lazy {@code @OneToOne} ({@code mappedBy}), whose owner Hibernate must query to know
 * whether there is one.</li>
 * </ul>
 * A lazy {@code @ManyToOne}, the owning side of a {@code @OneToOne} and collections stay lazy, through proxies.
 * {@code docs/adr/0004-no-hibernate-bytecode-enhancement.md} records why Play does not enhance.
 */
final class LazyAttributes {

    private LazyAttributes() {
    }

    /**
     * The attributes of {@code entities}, and of their mapped superclasses, that are declared lazy but load eagerly,
     * as {@code declaringClass.attribute}, sorted.
     */
    static List<String> loadedEagerly(Collection<Class<?>> entities) {
        Set<String> found = new TreeSet<>();
        for (Class<?> entity : entities) {
            for (Class<?> c = entity; c != null && isMapped(c); c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && needsEnhancement(f)) {
                        found.add(c.getName() + "." + f.getName());
                    }
                }
                for (Method m : c.getDeclaredMethods()) {
                    String property = propertyOf(m);
                    if (property != null && needsEnhancement(m)) {
                        found.add(c.getName() + "." + property);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    /** The WARN for a persistence unit whose {@code attributes} load eagerly. */
    static String warning(String unit, List<String> attributes) {
        return String.format("JPA (%s): %s declared lazy but loaded eagerly, because Play does not enhance entities with"
                + " Hibernate's bytecode enhancer: %s. See \"Lazy attributes\" in the JPA chapter of the manual.",
                unit, attributes.size() == 1 ? "this attribute is" : "these attributes are", String.join(", ", attributes));
    }

    private static boolean isMapped(Class<?> c) {
        return c.isAnnotationPresent(Entity.class) || c.isAnnotationPresent(MappedSuperclass.class);
    }

    private static boolean needsEnhancement(AnnotatedElement e) {
        if (e.isAnnotationPresent(Transient.class)) {
            return false;
        }
        Basic basic = e.getAnnotation(Basic.class);
        if (basic != null && basic.fetch() == FetchType.LAZY) {
            return true;
        }
        if (e.isAnnotationPresent(LazyGroup.class)) {
            return true;
        }
        OneToOne oneToOne = e.getAnnotation(OneToOne.class);
        return oneToOne != null && oneToOne.fetch() == FetchType.LAZY && !oneToOne.mappedBy().isEmpty();
    }

    /** The property a getter reads, as JPA's property access names it; null for any other method. */
    private static String propertyOf(Method m) {
        if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0 || m.isSynthetic() || m.isBridge()) {
            return null;
        }
        String name = m.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return Introspector.decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2 && (m.getReturnType() == boolean.class)) {
            return Introspector.decapitalize(name.substring(2));
        }
        return null;
    }
}
