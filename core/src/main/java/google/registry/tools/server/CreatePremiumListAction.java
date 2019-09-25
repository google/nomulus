// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.registry.Registries.assertTldExists;
import static google.registry.model.registry.label.PremiumListUtils.doesPremiumListExist;
import static google.registry.model.registry.label.PremiumListUtils.savePremiumListAndEntries;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.request.Action.Method.POST;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.joda.money.CurrencyUnit;

/**
 * An action that creates a premium list, for use by the {@code nomulus create_premium_list}
 * command.
 */
@Action(
    service = Action.Service.TOOLS,
    path = CreatePremiumListAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class CreatePremiumListAction extends CreateOrUpdatePremiumListAction {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String OVERRIDE_PARAM = "override";
  public static final String PATH = "/_dr/admin/createPremiumList";

  @Inject @Parameter(OVERRIDE_PARAM) boolean override;
  @Inject CreatePremiumListAction() {}

  @Override
  protected void saveToDatastore() {
    checkArgument(
        !doesPremiumListExist(name), "A premium list of this name already exists: %s.", name);
    if (!override) {
      assertTldExists(name);
    }

    logger.atInfo().log("Saving premium list for TLD %s", name);
    logInputData();
    List<String> inputDataPreProcessed =
        Splitter.on('\n').omitEmptyStrings().splitToList(inputData);
    PremiumList premiumList = new PremiumList.Builder().setName(name).build();
    savePremiumListAndEntries(premiumList, inputDataPreProcessed);

    String message =
        String.format(
            "Saved premium list %s with %d entries",
            premiumList.getName(), inputDataPreProcessed.size());
    logger.atInfo().log(message);
    response.setPayload(ImmutableMap.of("status", "success", "message", message));
  }

  @Override
  protected void saveToCloudSql() {
    if (!override) {
      assertTldExists(name);
    }
    logger.atInfo().log("Saving premium list to Cloud SQL for TLD %s", name);
    // TODO(mcilwain): Call logInputData() here once Datastore persistence is removed.
    List<String> inputDataPreProcessed =
        Splitter.on('\n').omitEmptyStrings().splitToList(inputData);

    ImmutableMap<String, PremiumListEntry> prices =
        new PremiumList.Builder().setName(name).build().parse(inputDataPreProcessed);
    ImmutableSet<CurrencyUnit> currencies =
        prices.values().stream()
            .map(e -> e.getValue().getCurrencyUnit())
            .distinct()
            .collect(toImmutableSet());
    checkArgument(
        currencies.size() == 1,
        "The Cloud SQL schema requires exactly one currency, but got: %s",
        currencies);
    CurrencyUnit currency = Iterables.getOnlyElement(currencies);

    Map<String, BigDecimal> priceAmounts =
        Maps.transformValues(prices, ple -> ple.getValue().getAmount());
    // TODO(mcilwain): Generate the Bloom filter and write it to Cloud SQL.
    jpaTm()
        .transact(
            () -> {
              checkArgument(
                  jpaTm()
                          .getEntityManager()
                          .createQuery("SELECT 1 FROM PremiumList WHERE name = :name", Long.class)
                          .setParameter("name", name)
                          .getSingleResult()
                      == 0,
                  "A premium list of this name already exists: %s.",
                  name);
              jpaTm()
                  .getEntityManager()
                  .persist(
                      google.registry.schema.tld.PremiumList.create(
                          name, jpaTm().getTransactionTime(), currency, priceAmounts));
            });

    String message =
        String.format("Saved premium list %s with %d entries", name, inputDataPreProcessed.size());
    logger.atInfo().log(message);
    // TODO(mcilwain): Call response.setPayload(...) here once Datastore persistence is removed.
  }
}
