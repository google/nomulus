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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.DatabaseMigrationUtils.suppressExceptionUnlessInTest;

import com.google.common.collect.Streams;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.schema.tld.PremiumListSqlDao;
import java.util.List;
import java.util.Optional;
import org.joda.money.BigMoney;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;

/**
 * DAO for {@link PremiumList} objects that handles the branching paths for SQL and Datastore.
 *
 * <p>For write actions, this class will perform the action against Cloud SQL then, after that
 * success or failure, against Datastore. If Datastore fails, an error is logged (but not thrown).
 *
 * <p>For read actions, when retrieving a price, we will log if the primary and secondary databases
 * have different values (or if the retrieval from Datastore fails).
 */
public class PremiumListDualDao {

  /**
   * Retrieves from Cloud SQL and returns the most recent premium list with the given name, or
   * absent if no such list exists.
   */
  public static Optional<PremiumList> getLatestRevision(String premiumListName) {
      return PremiumListSqlDao.getLatestRevision(premiumListName);
  }

  /**
   * Returns the premium price for the specified label and registry.
   *
   * <p>Returns absent if the label is not premium or there is no premium list for this registry.
   *
   * <p>Retrieves the price from both primary and secondary databases, and logs in the event of a
   * failure in Datastore (but does not throw an exception).
   */
  public static Optional<Money> getPremiumPrice(String label, Registry registry) {
    if (registry.getPremiumList() == null) {
      return Optional.empty();
    }
    String premiumListName = registry.getPremiumList().getName();
    Optional<Money> primaryResult;
      primaryResult = PremiumListSqlDao.getPremiumPrice(premiumListName, label);
    // Also load the value from Datastore, compare the two results, and log if different.
    suppressExceptionUnlessInTest(
        () -> {
          Optional<Money> secondaryResult =
              PremiumListDatastoreDao.getPremiumPrice(premiumListName, label, registry.getTldStr());
          checkState(
              primaryResult.equals(secondaryResult),
              "Unequal prices for domain %s.%s from primary SQL DB (%s) and secondary Datastore db"
                  + " (%s).",
              label,
              registry.getTldStr(),
              primaryResult,
              secondaryResult);
        },
        String.format(
            "Error loading price of domain %s.%s from Datastore.", label, registry.getTldStr()));
    return primaryResult;
  }

  /**
   * Saves the given list data to both primary and secondary databases.
   *
   * <p>Logs but doesn't throw an exception in the event of a failure when writing to Datastore.
   */
  public static PremiumList save(String name, List<String> inputData) {
    PremiumList result = PremiumListSqlDao.save(name, inputData);
      suppressExceptionUnlessInTest(
          () -> PremiumListDatastoreDao.save(name, inputData),
          "Error when saving premium list to Datastore.");
    return result;
  }

  /**
   * Deletes the premium list.
   *
   * <p>Logs but doesn't throw an exception in the event of a failure when deleting from Datastore.
   */
  public static void delete(PremiumList premiumList) {
      PremiumListSqlDao.delete(premiumList);
      suppressExceptionUnlessInTest(
          () -> PremiumListDatastoreDao.delete(premiumList),
          "Error when deleting premium list from Datastore.");
  }

  /** Returns whether or not there exists a premium list with the given name. */
  public static boolean exists(String premiumListName) {
    // It may seem like overkill, but loading the list has ways been the way we check existence and
    // given that we usually load the list around the time we check existence, we'll hit the cache
    return PremiumListSqlDao.getLatestRevision(premiumListName).isPresent();
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
      CurrencyUnit currencyUnit = premiumList.getCurrency();
      return Streams.stream(PremiumListSqlDao.loadPremiumListEntriesUncached(premiumList))
          .map(
              premiumEntry ->
                  new PremiumListEntry.Builder()
                      .setPrice(BigMoney.of(currencyUnit, premiumEntry.getPrice()).toMoney())
                      .setLabel(premiumEntry.getDomainLabel())
                      .build())
          .collect(toImmutableList());
    }

  private PremiumListDualDao() {}
}
