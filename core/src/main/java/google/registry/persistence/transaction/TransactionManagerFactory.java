// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import google.registry.persistence.DaggerPersistenceComponent;
import google.registry.tools.RegistryToolEnvironment;
import google.registry.util.NonFinalForTesting;
import google.registry.util.RegistryEnvironment;
import java.util.function.Supplier;

/** Factory class to create {@link TransactionManager} instance. */
public final class TransactionManagerFactory {

  private static final ImmutableSet<RegistryEnvironment> NON_SERVING_ENVS =
      ImmutableSet.of(RegistryEnvironment.UNITTEST, RegistryEnvironment.LOCAL);

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
    if (!NON_SERVING_ENVS.contains(RegistryEnvironment.get())) {
      return DaggerPersistenceComponent.create().jpaTransactionManager();
    } else {
      return DummyJpaTransactionManager.create();
    }
  }

  private static JpaTransactionManager createReplicaJpaTransactionManager() {
    if (RegistryEnvironment.get() != RegistryEnvironment.UNITTEST) {
      return DaggerPersistenceComponent.create().readOnlyReplicaJpaTransactionManager();
    } else {
      return DummyJpaTransactionManager.create();
    }
  }

  /**
   * Returns {@link JpaTransactionManager} instance.
   *
   * <p>Between invocations of {@link TransactionManagerFactory#setJpaTm} every call to this method
   * returns the same instance.
   */
  public static JpaTransactionManager tm() {
    return jpaTm.get();
  }

  /** Returns a read-only {@link JpaTransactionManager} instance if configured. */
  public static JpaTransactionManager replicaTm() {
    return replicaJpaTm.get();
  }

  /** Sets the return of {@link #tm()} to the given instance of {@link JpaTransactionManager}. */
  public static void setJpaTm(Supplier<JpaTransactionManager> jpaTmSupplier) {
    checkArgumentNotNull(jpaTmSupplier, "jpaTmSupplier");
    checkState(
        RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)
            || RegistryToolEnvironment.get() != null,
        "setJpaTm() should only be called by tools and tests.");
    jpaTm = Suppliers.memoize(jpaTmSupplier::get);
  }

  /** Sets the value of {@link #replicaTm()} to the given {@link JpaTransactionManager}. */
  public static void setReplicaJpaTm(Supplier<JpaTransactionManager> replicaJpaTmSupplier) {
    checkArgumentNotNull(replicaJpaTmSupplier, "replicaJpaTmSupplier");
    checkState(
        RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)
            || RegistryToolEnvironment.get() != null,
        "setReplicaJpaTm() should only be called by tools and tests.");
    replicaJpaTm = Suppliers.memoize(replicaJpaTmSupplier::get);
  }

  /**
   * Makes {@link #tm()} return the {@link JpaTransactionManager} instance provided by {@code
   * jpaTmSupplier} from now on. This method should only be called by an implementor of {@link
   * org.apache.beam.sdk.harness.JvmInitializer}.
   */
  public static void setJpaTmOnBeamWorker(Supplier<JpaTransactionManager> jpaTmSupplier) {
    checkArgumentNotNull(jpaTmSupplier, "jpaTmSupplier");
    jpaTm = Suppliers.memoize(jpaTmSupplier::get);
  }
}
