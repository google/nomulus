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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.pricing.PricingEngineProxy.getPricesForDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.template.soy.data.SoyMapData;
import google.registry.model.pricing.PremiumPricingEngine.DomainPrices;
import google.registry.tools.soy.DomainCreateSoyInfo;
import google.registry.util.StringGenerator;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** A command to create a new domain via EPP. */
@Parameters(separators = " =", commandDescription = "Create a new domain via EPP.")
final class CreateDomainCommand extends CreateOrUpdateDomainCommand
    implements CommandWithRemoteApi {

  @Parameter(
      names = "--period",
      description = "Initial registration period, in years.")
  private int period = 1;

  @Parameter(
      names = "--force_premiums",
      description = "Force the creation of premium domains.")
  private boolean forcePremiums;

  @Inject
  @Named("base64StringGenerator")
  StringGenerator passwordGenerator;

  private static final int PASSWORD_LENGTH = 16;

  @Override
  protected void initMutatingEppToolCommand() {
    checkArgumentNotNull(registrant, "Registrant must be specified");
    checkArgument(!admins.isEmpty(), "At least one admin must be specified");
    checkArgument(!techs.isEmpty(), "At least one tech must be specified");
    if (isNullOrEmpty(password)) {
      password = passwordGenerator.createString(PASSWORD_LENGTH);
    }

    for (String domain : domains) {
      String currency = null;
      String cost = null;
      DomainPrices prices = getPricesForDomainName(domain, DateTime.now(UTC));

      // Check if the domain is premium and set the fee on the create command if so.
      if (prices.isPremium()) {
        checkArgument(
            !force || forcePremiums,
            "Forced creates on premium domain(s) require --force_premiums");
        Money createCost = prices.getCreateCost();
        currency = createCost.getCurrencyUnit().getCode();
        cost = createCost.multipliedBy(period).getAmount().toString();
        System.out.printf(
            "NOTE: %s is premium at %s per year; sending total cost for %d year(s) of %s %s.\n",
            domain, createCost, period, currency, cost);
      }

      setSoyTemplate(DomainCreateSoyInfo.getInstance(), DomainCreateSoyInfo.DOMAINCREATE);
      addSoyRecord(
          clientId,
          new SoyMapData(
              "domain", domain,
              "period", period,
              "nameservers", nameservers,
              "registrant", registrant,
              "admins", admins,
              "techs", techs,
              "password", password,
              "currency", currency,
              "price", cost,
              "dsRecords", DsRecord.convertToSoy(dsRecords)));
    }
  }
}
