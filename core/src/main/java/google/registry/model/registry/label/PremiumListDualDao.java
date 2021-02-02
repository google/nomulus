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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.DatabaseMigrationUtils.suppressExceptionUnlessInTest;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.Streams;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.schema.tld.PremiumListSqlDao;
import java.util.List;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;

/**
 * DAO for {@link PremiumList} objects that handles the branching paths for SQL and Datastore.
 *
 * <p>For write actions, this class will perform the action against the primary database then, after
 * that success or failure, against the secondary database. If the secondary database fails, an
 * error is logged (but not thrown).
 *
 * <p>For read actions, when retrieving a price, we will log if the primary and secondary databases
 * have different values (or if the retrieval from the second database fails).
 *
 * <p>TODO (gbrodman): Change the isOfy() calls to the runtime selection of DBs when available
 */
public class PremiumListDualDao {

  /**
   * Retrieves from the appropriate DB and returns the most recent premium list with the given name,
   * or absent if no such list exists.
   */
  public static Optional<PremiumList> getLatestRevision(String premiumListName) {
    // TODO(gbrodman): Use Sarah's DB scheduler instead of this isOfy check
    if (tm().isOfy()) {
      return PremiumListDatastoreDao.getLatestRevision(premiumListName);
    } else {
      return PremiumListSqlDao.getLatestRevision(premiumListName);
    }
  }

  /**
   * Returns the premium price for the specified label and registry.
   *
   * <p>Returns absent if the label is not premium or there is no premium list for this registry.
   *
   * <p>Retrieves the price from both primary and secondary databases, and logs in the event of a
   * failure in the secondary (but does not throw an exception).
   */
  public static Optional<Money> getPremiumPrice(String label, Registry registry) {
    if (registry.getPremiumList() == null) {
      return Optional.empty();
    }
    String premiumListName = registry.getPremiumList().getName();
    Optional<Money> primaryResult;
    // TODO(gbrodman): Use Sarah's DB scheduler instead of this isOfy check
    if (tm().isOfy()) {
      primaryResult =
          PremiumListDatastoreDao.getPremiumPrice(premiumListName, label, registry.getTldStr());
    } else {
      primaryResult = PremiumListSqlDao.getPremiumPrice(premiumListName, label);
    }
    // Also load the value from the secondary DB, compare the two results, and log if different.
    // TODO(gbrodman): Use Sarah's DB scheduler instead of this isOfy check
    if (tm().isOfy()) {
      suppressExceptionUnlessInTest(
          () -> {
            Optional<Money> secondaryResult =
                PremiumListSqlDao.getPremiumPrice(premiumListName, label);
            if (!primaryResult.equals(secondaryResult)) {
              throw new IllegalStateException(
                  String.format(
                      "Unequal prices for domain %s.%s from primary Datastore DB (%s) and "
                          + "secondary SQL db (%s).",
                      label, registry.getTldStr(), primaryResult, secondaryResult));
            }
          },
          String.format(
              "Error loading price of domain %s.%s from Cloud SQL.", label, registry.getTldStr()));
    } else {
      suppressExceptionUnlessInTest(
          () -> {
            Optional<Money> secondaryResult =
                PremiumListDatastoreDao.getPremiumPrice(
                    premiumListName, label, registry.getTldStr());
            if (!primaryResult.equals(secondaryResult)) {
              throw new IllegalStateException(
                  String.format(
                      "Unequal prices for domain %s.%s from primary SQL DB (%s) and secondary "
                          + "Datastore db (%s).",
                      label, registry.getTldStr(), primaryResult, secondaryResult));
            }
          },
          String.format(
              "Error loading price of domain %s.%s from Datastore.", label, registry.getTldStr()));
    }
    return primaryResult;
  }

  /**
   * Saves the given list data to both primary and secondary databases.
   *
   * <p>Logs but doesn't throw an exception in the event of a failure when writing to the secondary
   * database.
   */
  public static PremiumList save(String name, List<String> inputData) {
    PremiumList result;
    // TODO(gbrodman): Use Sarah's DB scheduler instead of this isOfy check
    if (tm().isOfy()) {
      result = PremiumListDatastoreDao.save(name, inputData);
      suppressExceptionUnlessInTest(
          () -> PremiumListSqlDao.save(name, inputData), "Error when saving premium list to SQL.");
    } else {
      result = PremiumListSqlDao.save(name, inputData);
      suppressExceptionUnlessInTest(
          () -> PremiumListDatastoreDao.save(name, inputData),
          "Error when saving premium list to Datastore.");
    }
    return result;
  }

  /**
   * Deletes the premium list.
   *
   * <p>Logs but doesn't throw an exception in the event of a failure when deleting from the
   * secondary database.
   */
  public static void delete(PremiumList premiumList) {
    // TODO(gbrodman): Use Sarah's DB scheduler instead of this isOfy check
    if (tm().isOfy()) {
      PremiumListDatastoreDao.delete(premiumList);
      suppressExceptionUnlessInTest(
          () -> PremiumListSqlDao.delete(premiumList),
          "Error when deleting premium list from SQL.");
    } else {
      PremiumListSqlDao.delete(premiumList);
      suppressExceptionUnlessInTest(
          () -> PremiumListDatastoreDao.delete(premiumList),
          "Error when deleting premium list from Datastore.");
    }
  }

  /** Returns whether or not there exists a premium list with the given name. */
  public static boolean exists(String premiumListName) {
    // It may seem like overkill, but loading the list has ways been the way we check existence and
    // given that we usually load the list around the time we check existence, we'll hit the cache
    // TODO(gbrodman): Use Sarah's DB scheduler instead of this isOfy check
    if (tm().isOfy()) {
      return PremiumListDatastoreDao.getLatestRevision(premiumListName).isPresent();
    } else {
      return PremiumListSqlDao.getLatestRevision(premiumListName).isPresent();
    }
  }

  /**
   * Returns all {@link PremiumListEntry PremiumListEntries} in the list with the given name.
   *
   * <p>This is an expensive operation and should only be used when the entire list is required.
   */
  public static Iterable<PremiumListEntry> loadAllPremiumListEntries(String premiumListName) {
    PremiumList premiumList =
        getLatestRevision(premiumListName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("No premium list with name %s.", premiumListName)));
    // TODO(gbrodman): Use Sarah's DB scheduler instead of this isOfy check
    if (tm().isOfy()) {
      return PremiumListDatastoreDao.loadPremiumListEntriesUncached(premiumList);
    } else {
      CurrencyUnit currencyUnit = premiumList.getCurrency();
      return Streams.stream(PremiumListSqlDao.loadPremiumListEntriesUncached(premiumList))
          .map(
              premiumEntry ->
                  new PremiumListEntry.Builder()
                      .setPrice(Money.of(currencyUnit, premiumEntry.getPrice()))
                      .setLabel(premiumEntry.getDomainLabel())
                      .build())
          .collect(toImmutableList());
    }
  }

  private PremiumListDualDao() {}
}
