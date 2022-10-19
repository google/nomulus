// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
//
package google.registry.model;

import static com.google.common.base.Preconditions.checkState;

import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.common.flogger.FluentLogger;
import google.registry.beam.common.RegistryPipelineWorkerInitializer;
import google.registry.config.RegistryEnvironment;
import google.registry.model.annotations.DeleteAfterMigration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Allocates a {@link long} to use as a {@code @Id}, (part) of the primary SQL key for an entity.
 *
 * <p>Normally, the ID is globally unique and allocated by Datastore. It is possible to override
 * this behavior by providing an ID supplier, such as in unit tests, where a self-allocated ID based
 * on a monotonically increasing atomic {@link long} is used. Such an ID supplier can also be used
 * in other scenarios, such as in a Beam pipeline to get around the limitation of Beam's inability
 * to use GAE SDK to access Datastore. The override should be used with great care lest it results
 * in irreversible data corruption.
 *
 * @see #setIdSupplier(Supplier)
 */
@DeleteAfterMigration
public final class IdService {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private IdService() {}

  private static Supplier<Long> idSupplier =
      RegistryEnvironment.UNITTEST.equals(RegistryEnvironment.get())
          ? SelfAllocatedIdSupplier.getInstance()
          : DatastoreIdSupplier.getInstance();

  /**
   * Provides a {@link Supplier} of ID that overrides the default.
   *
   * <p>Currently, the only use case for an override is in the Beam pipeline, where access to
   * Datastore is not possible through the App Engine API. As such, the setter explicitly checks if
   * the runtime is Beam.
   *
   * <p>Because the provided supplier is not guaranteed to be globally unique and compatible with
   * existing IDs in the database, one should proceed with great care. It is safe to use an
   * arbitrary supplier when the resulting IDs are not significant and not persisted back to the
   * database, i.e. the IDs are only required by the {@link Buildable} contract but are not used in
   * any meaningful way. One example is the RDE pipeline where we project EPP resource entities from
   * history entries to watermark time, which are then marshalled into XML elements in the RDE
   * deposits, where the IDs are omitted.
   */
  public static void setIdSupplier(Supplier<Long> idSupplier) {
    checkState(
        "true".equals(System.getProperty(RegistryPipelineWorkerInitializer.PROPERTY, "false")),
        "Can only set ID supplier in a Beam pipeline");
    logger.atWarning().log("Using ID supplier override!");
    IdService.idSupplier = idSupplier;
  }

  /** Allocates an id. */
  public static long allocateId() {
    return idSupplier.get();
  }

  // TODO(b/201547855): Find a way to allocate a unique ID without datastore.
  private static class DatastoreIdSupplier implements Supplier<Long> {

    private static final DatastoreIdSupplier INSTANCE = new DatastoreIdSupplier();

    /**
     * A placeholder String passed into {@code DatastoreService.allocateIds} that ensures that all
     * IDs are initialized from the same ID pool.
     */
    private static final String APP_WIDE_ALLOCATION_KIND = "common";

    public static DatastoreIdSupplier getInstance() {
      return INSTANCE;
    }

    @Override
    public Long get() {
      return DatastoreServiceFactory.getDatastoreService()
          .allocateIds(APP_WIDE_ALLOCATION_KIND, 1)
          .iterator()
          .next()
          .getId();
    }
  }

  /**
   * An ID supplier that allocates an ID from a monotonically increasing atomic {@link long}.
   *
   * <p>The generated IDs are only unique within the same JVM. It is not suitable for production use
   * unless in cases the IDs are not significant.
   */
  public static class SelfAllocatedIdSupplier implements Supplier<Long> {

    private static final SelfAllocatedIdSupplier INSTANCE = new SelfAllocatedIdSupplier();

    /** Counts of used ids for self allocating IDs. */
    private static final AtomicLong nextSelfAllocatedId = new AtomicLong(1); // ids cannot be zero

    public static SelfAllocatedIdSupplier getInstance() {
      return INSTANCE;
    }

    @Override
    public Long get() {
      return nextSelfAllocatedId.getAndIncrement();
    }

    public void reset() {
      nextSelfAllocatedId.set(1);
    }
  }
}
