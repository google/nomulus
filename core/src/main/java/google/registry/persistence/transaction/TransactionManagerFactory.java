// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.persistence.transaction;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.joda.time.DateTimeZone.UTC;

import com.google.appengine.api.utils.SystemProperty;
import com.google.appengine.api.utils.SystemProperty.Environment.Value;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import google.registry.config.RegistryEnvironment;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.PrimaryDatabase;
import google.registry.model.ofy.DatastoreTransactionManager;
import google.registry.persistence.DaggerPersistenceComponent;
import google.registry.tools.RegistryToolEnvironment;
import google.registry.util.NonFinalForTesting;
import java.util.Optional;
import java.util.function.Supplier;
import org.joda.time.DateTime;

/** Factory class to create {@link TransactionManager} instance. */
// TODO: Rename this to PersistenceFactory and move to persistence package.
public class TransactionManagerFactory {

  private static final DatastoreTransactionManager ofyTm = createTransactionManager();

  /** Optional override to manually set the transaction manager per-test. */
  private static Optional<TransactionManager> tmForTest = Optional.empty();

  /** Supplier for jpaTm so that it is initialized only once, upon first usage. */
  @NonFinalForTesting
  private static Supplier<JpaTransactionManager> jpaTm =
      Suppliers.memoize(TransactionManagerFactory::createJpaTransactionManager);

  private TransactionManagerFactory() {}

  private static JpaTransactionManager createJpaTransactionManager() {
    // If we are running a nomulus command, jpaTm will be injected in RegistryCli.java
    // by calling setJpaTm().
    if (isInAppEngine()) {
      return DaggerPersistenceComponent.create().appEngineJpaTransactionManager();
    } else {
      return DummyJpaTransactionManager.create();
    }
  }

  private static DatastoreTransactionManager createTransactionManager() {
    return new DatastoreTransactionManager(null);
  }

  /**
   * This function uses App Engine API to determine if the current runtime environment is App
   * Engine.
   *
   * @see <a
   *     href="https://cloud.google.com/appengine/docs/standard/java/javadoc/com/google/appengine/api/utils/SystemProperty">App
   *     Engine API public doc</a>
   */
  private static boolean isInAppEngine() {
    // SystemProperty.environment.value() returns null if the current runtime is local JVM
    return SystemProperty.environment.value() == Value.Production;
  }

  /**
   * Returns the {@link TransactionManager} instance.
   *
   * <p>Returns the {@link JpaTransactionManager} or {@link DatastoreTransactionManager} based on
   * the migration schedule or the manually specified per-test transaction manager.
   */
  public static TransactionManager tm() {
    if (tmForTest.isPresent()) {
      return tmForTest.get();
    }
    return DatabaseMigrationStateSchedule.getValueAtTime(DateTime.now(UTC))
            .getPrimaryDatabase()
            .equals(PrimaryDatabase.DATASTORE)
        ? ofyTm()
        : jpaTm();
  }

  /**
   * Returns {@link JpaTransactionManager} instance.
   *
   * <p>Between invocations of {@link TransactionManagerFactory#setJpaTm} every call to this method
   * returns the same instance.
   */
  public static JpaTransactionManager jpaTm() {
    return jpaTm.get();
  }

  /** Returns {@link DatastoreTransactionManager} instance. */
  @VisibleForTesting
  public static DatastoreTransactionManager ofyTm() {
    return ofyTm;
  }

  /** Sets the return of {@link #jpaTm()} to the given instance of {@link JpaTransactionManager}. */
  public static void setJpaTm(Supplier<JpaTransactionManager> jpaTmSupplier) {
    checkNotNull(jpaTmSupplier, "jpaTmSupplier");
    checkState(
        RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)
            || RegistryToolEnvironment.get() != null,
        "setJpamTm() should only be called by tools and tests.");
    jpaTm = Suppliers.memoize(jpaTmSupplier::get);
  }

  /**
   * Makes {@link #jpaTm()} return the {@link JpaTransactionManager} instance provided by {@code
   * jpaTmSupplier} from now on. This method should only be called by an implementor of {@link
   * org.apache.beam.sdk.harness.JvmInitializer}.
   */
  public static void setJpaTmOnBeamWorker(Supplier<JpaTransactionManager> jpaTmSupplier) {
    checkNotNull(jpaTmSupplier, "jpaTmSupplier");
    jpaTm = Suppliers.memoize(jpaTmSupplier::get);
  }

  /**
   * Sets the return of {@link #tm()} to the given instance of {@link TransactionManager}.
   *
   * <p>DO NOT CALL THIS DIRECTLY IF POSSIBLE. Strongly prefer the use of <code>TmOverrideExtension
   * </code> in test code instead.
   *
   * <p>Used when overriding the per-test transaction manager for dual-database tests. Should be
   * matched with a corresponding invocation of {@link #removeTmOverrideForTest()} either at the end
   * of the test or in an <code>@AfterEach</code> handler.
   */
  @VisibleForTesting
  public static void setTmOverrideForTest(TransactionManager newTmOverride) {
    tmForTest = Optional.of(newTmOverride);
  }

  /** Resets the overridden transaction manager post-test. */
  public static void removeTmOverrideForTest() {
    tmForTest = Optional.empty();
  }

  public static void assertNotReadOnlyMode() {
    if (DatabaseMigrationStateSchedule.getValueAtTime(DateTime.now(UTC)).isReadOnly()) {
      throw new ReadOnlyModeException();
    }
  }

  /** Registry is currently undergoing maintenance and is in read-only mode. */
  public static class ReadOnlyModeException extends IllegalStateException {
    ReadOnlyModeException() {
      super("Registry is currently undergoing maintenance and is in read-only mode");
    }
  }
}
