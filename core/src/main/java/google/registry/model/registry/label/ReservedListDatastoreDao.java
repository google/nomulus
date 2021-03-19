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

package google.registry.model.registry.label;

import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.googlecode.objectify.Key;
import google.registry.persistence.VKey;
import java.util.Optional;

/** A {@link ReservedList} DAO for Datastore. */
public class ReservedListDatastoreDao {

  private ReservedListDatastoreDao() {}

  /** Persist a new reserved list to Datastore. */
  public static void save(ReservedList reservedList) {
    ofyTm().transact(() -> ofyTm().put(reservedList));
  }

  /** Delete a reserved list from Datastore. */
  public static void delete(ReservedList reservedList) {
    ofyTm().transact(() -> ofyTm().delete(reservedList));
  }

  /**
   * Returns the most recent revision of the {@link ReservedList} with the specified name, if it
   * exists.
   */
  public static Optional<ReservedList> getLatestRevision(String reservedListName) {
    return ofyTm()
        .loadByKeyIfPresent(
            VKey.createOfy(
                ReservedList.class,
                Key.create(getCrossTldKey(), ReservedList.class, reservedListName)));
  }
}
