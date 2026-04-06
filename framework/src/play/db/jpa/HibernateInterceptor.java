package play.db.jpa;

import org.hibernate.Interceptor;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.type.Type;

public class HibernateInterceptor implements Interceptor {

  public HibernateInterceptor() {
  }

  @Override
  public int[] findDirty(Object o, Object id, Object[] arg2, Object[] arg3, String[] arg4, Type[] arg5) {
    if (o instanceof JPABase && !((JPABase) o).willBeSaved) {
      return new int[0];
    }
    return null;
  }

    @Override
    public void onCollectionUpdate(Object collection, Object key) {
        // Note: with patched Hibernate, this method returns boolean to control
        // whether the collection update proceeds (willBeSaved check).
        // With unpatched Hibernate, findDirty() handles the skip logic.
    }

    @Override
    public void onCollectionRecreate(Object collection, Object key) {
    }

    @Override
    public void onCollectionRemove(Object collection, Object key) {
    }

    protected final ThreadLocal<Object> entities = new ThreadLocal<>();

    @Override
    public boolean onPersist(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
        entities.set(entity);
        return Interceptor.super.onPersist(entity, id, state, propertyNames, types);
    }

    @Override
    public void afterTransactionCompletion(org.hibernate.Transaction tx) {
        entities.remove();
    }
}