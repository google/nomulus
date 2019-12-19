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

package google.registry.schema.tld;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.Iterables;
import java.util.List;
import java.util.Optional;

/** Data access object class for {@link PremiumList}. */
public class PremiumListDao {

  /** Persist a new premium list to Cloud SQL. */
  public static void saveNew(PremiumList premiumList) {
    jpaTm()
        .transact(
            () -> {
              checkArgument(
                  !checkExists(premiumList.getName()),
                  "A premium list of this name already exists: %s.",
                  premiumList.getName());
              jpaTm().getEntityManager().persist(premiumList);
            });
  }

  /** Updates an existing premium list to Cloud SQL (i.e. writes a new revision). */
  public static void update(PremiumList premiumList) {
    jpaTm()
        .transact(
            () -> {
              checkArgument(
                  checkExists(premiumList.getName()),
                  "Can't update premium list '%s' because it doesn't exist",
                  premiumList.getName());
              // This calls persist() not merge() because it's a new revision, i.e. a new primary
              // key. It shares the same name as previously existing lists and has a higher revision
              // id.
              jpaTm().getEntityManager().persist(premiumList);
            });
  }

  /**
   * Returns the most recent revision of the premium list with the given name, if one exists.
   *
   * <p>This does NOT populate the price list from the <code>PremiumEntry</code> table.
   */
  public static Optional<PremiumList> getCurrentRevision(String name) {
    return jpaTm()
        .transact(
            () -> {
              List<PremiumList> persistedLists =
                  jpaTm()
                      .getEntityManager()
                      .createQuery(
                          "SELECT pl FROM PremiumList pl WHERE pl.name = :name"
                              + " ORDER BY revisionId DESC",
                          PremiumList.class)
                      .setParameter("name", name)
                      .setMaxResults(1)
                      .getResultList();
              return persistedLists.isEmpty()
                  ? Optional.empty()
                  : Optional.of(Iterables.getOnlyElement(persistedLists));
            });
  }

  /**
   * Returns whether the premium list of the given name exists.
   *
   * <p>This means that at least one premium list revision must exist for the given name.
   */
  public static boolean checkExists(String premiumListName) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                        .getEntityManager()
                        .createQuery("SELECT 1 FROM PremiumList WHERE name = :name", Integer.class)
                        .setParameter("name", premiumListName)
                        .setMaxResults(1)
                        .getResultList()
                        .size()
                    > 0);
  }

  private PremiumListDao() {}
}
