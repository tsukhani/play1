package play.db.jpa;

import org.hibernate.Interceptor;
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
}