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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocates a {@code long} to use as a {@code @Id}, (part) of the primary SQL key for an entity.
 */
public final class IdService {

  private IdService() {}

  /**
   * A SQL Sequence based ID allocator that generates an ID from a monotonically increasing {@link
   * AtomicLong}
   *
   * <p>The generated IDs are project-wide unique.
   */
  public static long allocateId() {
    return tm().transact(
            () ->
                (BigInteger)
                    tm().getEntityManager()
                        .createNativeQuery("SELECT nextval('project_wide_unique_id_seq')")
                        .getSingleResult())
        .longValue();
  }
}
