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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.appengine.api.utils.SystemProperty;
import com.google.appengine.api.utils.SystemProperty.Environment.Value;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import google.registry.config.RegistryEnvironment;
import google.registry.persistence.DaggerPersistenceComponent;
import google.registry.tools.RegistryToolEnvironment;
import google.registry.util.NonFinalForTesting;
import java.util.Optional;
import java.util.function.Supplier;

/** Factory class to create {@link TransactionManager} instance. */
// TODO: Rename this to PersistenceFactory and move to persistence package.
public final class TransactionManagerFactory {

  /** Optional override to manually set the transaction manager per-test. */
  private static Optional<TransactionManager> tmForTest = Optional.empty();

  /** Supplier for jpaTm so that it is initialized only once, upon first usage. */
  @NonFinalForTesting
  private static Supplier<JpaTransactionManager> jpaTm =
      Suppliers.memoize(TransactionManagerFactory::createJpaTransactionManager);

  @NonFinalForTesting
  private static Supplier<JpaTransactionManager> replicaJpaTm =
      Suppliers.memoize(TransactionManagerFactory::createReplicaJpaTransactionManager);

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

  private static JpaTransactionManager createReplicaJpaTransactionManager() {
    if (isInAppEngine()) {
      return DaggerPersistenceComponent.create().readOnlyReplicaJpaTransactionManager();
    } else {
      return DummyJpaTransactionManager.create();
    }
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
   * <p>Returns the {@link JpaTransactionManager} or replica based on the possible manually
   * specified per-test transaction manager.
   */
  public static TransactionManager tm() {
    return tmForTest.orElseGet(TransactionManagerFactory::jpaTm);
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

  /** Returns a read-only {@link JpaTransactionManager} instance if configured. */
  public static JpaTransactionManager replicaJpaTm() {
    return replicaJpaTm.get();
  }

  /**
   * Returns a {@link TransactionManager} that uses a replica database if one exists.
   *
   * <p>In Datastore mode, this is unchanged from the regular transaction manager. In SQL mode,
   * however, this will be a reference to the read-only replica database if one is configured.
   */
  public static TransactionManager replicaTm() {
    return tm().isOfy() ? tm() : replicaJpaTm();
  }

  /** Sets the return of {@link #jpaTm()} to the given instance of {@link JpaTransactionManager}. */
  public static void setJpaTm(Supplier<JpaTransactionManager> jpaTmSupplier) {
    checkArgumentNotNull(jpaTmSupplier, "jpaTmSupplier");
    checkState(
        RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)
            || RegistryToolEnvironment.get() != null,
        "setJpamTm() should only be called by tools and tests.");
    jpaTm = Suppliers.memoize(jpaTmSupplier::get);
  }

  /** Sets the value of {@link #replicaJpaTm()} to the given {@link JpaTransactionManager}. */
  public static void setReplicaJpaTm(Supplier<JpaTransactionManager> replicaJpaTmSupplier) {
    checkArgumentNotNull(replicaJpaTmSupplier, "replicaJpaTmSupplier");
    checkState(
        RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)
            || RegistryToolEnvironment.get() != null,
        "setReplicaJpaTm() should only be called by tools and tests.");
    replicaJpaTm = Suppliers.memoize(replicaJpaTmSupplier::get);
  }

  /**
   * Makes {@link #jpaTm()} return the {@link JpaTransactionManager} instance provided by {@code
   * jpaTmSupplier} from now on. This method should only be called by an implementor of {@link
   * org.apache.beam.sdk.harness.JvmInitializer}.
   */
  public static void setJpaTmOnBeamWorker(Supplier<JpaTransactionManager> jpaTmSupplier) {
    checkArgumentNotNull(jpaTmSupplier, "jpaTmSupplier");
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
}
