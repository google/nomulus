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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.joda.time.DateTimeZone.UTC;

import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.common.flogger.FluentLogger;
import google.registry.beam.common.RegistryPipelineWorkerInitializer;
import google.registry.config.RegistryEnvironment;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.joda.time.DateTime;

/**
 * Allocates a {@link long} to use as a {@code @Id}, (part) of the primary SQL key for an entity.
 */
public final class IdService {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // TODO(ptkach): remove once the Cloud SQL sequence-based method is live in production
  private static boolean forceUseSelfAllocateId = false;

  public static void setForceUseSelfAllocatedId() {
    checkState(
        "true".equals(System.getProperty(RegistryPipelineWorkerInitializer.PROPERTY, "false")),
        "Can only set ID supplier in a Beam pipeline");
    logger.atWarning().log("Using ID supplier override!");
    IdService.forceUseSelfAllocateId = true;
  }

  private static class SelfAllocatedIdSupplier implements Supplier<Long> {

    private static final SelfAllocatedIdSupplier INSTANCE = new SelfAllocatedIdSupplier();

    /** Counts of used ids for self allocating IDs. */
    private static final AtomicLong nextSelfAllocatedId = new AtomicLong(1); // ids cannot be zero

    private static SelfAllocatedIdSupplier getInstance() {
      return INSTANCE;
    }

    @Override
    public Long get() {
      return nextSelfAllocatedId.getAndIncrement();
    }
  }
  /**
   * A SQL Sequence based ID allocator that generates an ID from a monotonically increasing atomic
   * {@link long}
   *
   * <p>The generated IDs are project-wide unique
   */
  private static Long getSequenceBasedId() {
    return jpaTm()
        .transact(
            () ->
                (BigInteger)
                    jpaTm()
                        .getEntityManager()
                        .createNativeQuery("SELECT nextval('project_wide_unique_id_seq')")
                        .getSingleResult())
        .longValue();
  }

  // TODO(ptkach): Remove once all instances switch to sequenceBasedId
  /**
   * A Datastore based ID allocator that generates an ID from a monotonically increasing atomic
   * {@link long}
   *
   * <p>The generated IDs are project-wide unique
   */
  private static Long getDatastoreBasedId() {
    return DatastoreServiceFactory.getDatastoreService()
        .allocateIds("common", 1)
        .iterator()
        .next()
        .getId();
  }

  private IdService() {}

  public static long allocateId() {
    if (DatabaseMigrationStateSchedule.getValueAtTime(DateTime.now(UTC))
            .equals(MigrationState.SEQUENCE_BASED_ALLOCATE_ID)
        || RegistryEnvironment.UNITTEST.equals(RegistryEnvironment.get())) {
      return getSequenceBasedId();
    } else if (IdService.forceUseSelfAllocateId) {
      return SelfAllocatedIdSupplier.getInstance().get();
    }
    return getDatastoreBasedId();
  }
}
