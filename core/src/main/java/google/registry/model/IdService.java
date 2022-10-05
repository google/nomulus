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
import com.google.common.annotations.VisibleForTesting;
import google.registry.config.RegistryEnvironment;
import google.registry.model.annotations.DeleteAfterMigration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocates a globally unique {@link Long} number to use as a {@code @Id}, which is usually used as
 * (part) of the primary SQL key.
 *
 * <p>Normally, the ID is generated by Datastore. In unit tests or otherwise specified by the {@link
 * #isSelfAllocated} static field, it is an atomic long number that's incremented every time this
 * method is called.
 */
@DeleteAfterMigration
public final class IdService {

  /**
   * A placeholder String passed into DatastoreService.allocateIds that ensures that all ids are
   * initialized from the same id pool.
   */
  private static final String APP_WIDE_ALLOCATION_KIND = "common";

  /**
   * The override to self allocate the ID. Other than in tests (which already self allocates), it is
   * almost always a bad idea to self allocate an ID, which is used as the primary key in the
   * database almost universally. This override should only be used when accessing datastore is not
   * possible and when the allocated ID is not important or persisted back to the database. One
   * example is the RDE beam pipeline where we create EPP resource entities from history entries
   * which are then marshalled into XML elements in the RDE deposits.
   */
  private static boolean isSelfAllocated = false;

  /**
   * Counts of used ids for self allocating IDs.
   *
   * @see #isSelfAllocated
   */
  private static final AtomicLong nextSelfAllocatedId = new AtomicLong(1); // ids cannot be zero

  /**
   * Make the ID self allocated in the current JVM.
   *
   * <p>Always use caution when enabling this option as it can be very dangerous when you write
   * these self-allocated IDs back to the database.
   *
   * @see #isSelfAllocated
   */
  public static void useSelfAllocatedId() {
    isSelfAllocated = true;
  }

  private static boolean isSelfAllocated() {
    return isSelfAllocated || RegistryEnvironment.UNITTEST.equals(RegistryEnvironment.get());
  }

  /** Allocates an id. */
  // TODO(b/201547855): Find a way to allocate a unique ID without datastore.
  public static long allocateId() {
    return isSelfAllocated()
        ? nextSelfAllocatedId.getAndIncrement()
        : DatastoreServiceFactory.getDatastoreService()
            .allocateIds(APP_WIDE_ALLOCATION_KIND, 1)
            .iterator()
            .next()
            .getId();
  }

  /** Resets the global self-allocated id counter (i.e. sets the next id to 1). */
  @VisibleForTesting
  public static void resetSelfAllocatedId() {
    checkState(
        isSelfAllocated(),
        "Can only call resetSelfAllocatedId() when IdService is set to self allocate.");
    nextSelfAllocatedId.set(1); // ids cannot be zero
  }
}
