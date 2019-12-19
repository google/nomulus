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
import static com.google.common.flogger.LazyArgs.lazy;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.request.JsonResponse;
import google.registry.request.Parameter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.joda.money.CurrencyUnit;

/** Abstract base class for actions that update premium lists. */
public abstract class CreateOrUpdatePremiumListAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int MAX_LOGGING_PREMIUM_LIST_LENGTH = 1000;

  public static final String NAME_PARAM = "name";
  public static final String INPUT_PARAM = "inputData";

  @Inject JsonResponse response;

  @Inject
  @Parameter("premiumListName")
  String name;

  @Inject
  @Parameter(INPUT_PARAM)
  String inputData;

  @Override
  public void run() {
    try {
      saveToDatastore();
    } catch (IllegalArgumentException e) {
      logger.atInfo().withCause(e).log(
          "Usage error in attempting to save premium list from nomulus tool command");
      response.setPayload(ImmutableMap.of("error", e.toString(), "status", "error"));
      return;
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Unexpected error saving premium list to Datastore from nomulus tool command");
      response.setPayload(ImmutableMap.of("error", e.toString(), "status", "error"));
      return;
    }

    try {
      saveToCloudSql();
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log(
          "Unexpected error saving premium list to Cloud SQL from nomulus tool command");
      response.setPayload(ImmutableMap.of("error", e.toString(), "status", "error"));
    }
  }

  static google.registry.schema.tld.PremiumList parseToPremiumList(String name, String inputData) {
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
        ImmutableSortedSet.copyOf(currencies));
    CurrencyUnit currency = Iterables.getOnlyElement(currencies);

    Map<String, BigDecimal> priceAmounts =
        Maps.transformValues(prices, ple -> ple.getValue().getAmount());
    return google.registry.schema.tld.PremiumList.create(name, currency, priceAmounts);
  }

  /** Logs the premium list data at INFO, truncated if too long. */
  void logInputData() {
    logger.atInfo().log(
        "Received the following input data: %s",
        lazy(
            () ->
                (inputData.length() < MAX_LOGGING_PREMIUM_LIST_LENGTH)
                    ? inputData
                    : (inputData.substring(0, MAX_LOGGING_PREMIUM_LIST_LENGTH) + "<truncated>")));
  }

  /** Saves the premium list to Datastore. */
  protected abstract void saveToDatastore();

  /** Saves the premium list to Cloud SQL. */
  protected abstract void saveToCloudSql();
}
