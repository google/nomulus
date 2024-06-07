// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
package google.registry.persistence.transaction;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides {@code long} values for use as {@code id} by JPA model entities in (read-only)
 * transactions in the replica database. Each id is only unique in the JVM instance.
 *
 * <p>The {@link IdService database sequence-based id service} cannot be used with the replica
 * because id generation is a write operation.
 */
final class ReplicaDbIdService {

  private ReplicaDbIdService() {}

  private static final AtomicLong nextId = new AtomicLong(1);

  /**
   * Returns the next long value from a {@link AtomicLong}. Each id is unique in the JVM instance.
   */
  static final long allocatedId() {
    return nextId.getAndIncrement();
  }
}
