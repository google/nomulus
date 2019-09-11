package google.registry.model.transaction;

import dagger.Module;
import dagger.Provides;
import google.registry.model.ofy.DatastoreTransactionManager;
import google.registry.persistence.PersistenceModule.AppEngineEMF;
import google.registry.util.SystemClock;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import javax.persistence.EntityManagerFactory;

/** Dagger module to provides {@link TransactionManager} instances. */
@Module
public class TransactionManagerModule {
  @Provides
  @Singleton
  @AppEngineTM
  public static DatabaseTransactionManager providesDatabaseTransactionManager(
      @AppEngineEMF EntityManagerFactory emf) {
    return new DatabaseTransactionManager(emf, new SystemClock());
  }

  @Provides
  @Singleton
  public static DatastoreTransactionManager providesDatastoreTransactionManager() {
    return new DatastoreTransactionManager(null);
  }

  /** Dagger qualifier for the {@link EntityManagerFactory} used for App Engine application. */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  public @interface AppEngineTM {}
}
