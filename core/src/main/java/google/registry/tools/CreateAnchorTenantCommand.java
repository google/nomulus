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
import static google.registry.model.tld.Registries.findTldForNameOrThrow;
import static google.registry.pricing.PricingEngineProxy.getDomainCreateCost;
import static google.registry.util.StringGenerator.DEFAULT_PASSWORD_LENGTH;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.net.InternetDomainName;
import com.google.template.soy.data.SoyMapData;
import google.registry.tools.soy.CreateAnchorTenantSoyInfo;
import google.registry.util.StringGenerator;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** A command to create a new anchor tenant domain. */
@Parameters(separators = " =", commandDescription = "Provision a domain for an anchor tenant.")
final class CreateAnchorTenantCommand extends MutatingEppToolCommand {

  private static final int DEFAULT_ANCHOR_TENANT_PERIOD_YEARS = 2;

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as",
      required = true)
  String clientId;

  @Parameter(
      names = {"-n", "--domain_name"},
      description = "Domain to create.",
      required = true)
  private String domainName;

  @Parameter(
      names = {"--contact"},
      description = "Contact ID for the request. This will be used for registrant, admin contact, "
          + "and tech contact.",
      required = true)
  private String contact;

  @Parameter(
      names = {"--reason"},
      description = "Reason for the change.")
  private String reason;

  @Parameter(
      names = {"--password"},
      description = "Password. Optional, randomly generated if not provided.")
  private String password;

  @Parameter(
      names = {"--fee"},
      description = "Include fee extension in EPP (required for premium domains).")
  private boolean fee;

  @Inject
  @Named("base64StringGenerator")
  StringGenerator passwordGenerator;

  @Override
  protected void initMutatingEppToolCommand() {
    checkArgument(superuser, "This command must be run as a superuser.");
    findTldForNameOrThrow(InternetDomainName.from(domainName)); // Check that the tld exists.
    if (isNullOrEmpty(password)) {
      password = passwordGenerator.createString(DEFAULT_PASSWORD_LENGTH);
    }

    Money cost = null;
    if (fee) {
      cost = getDomainCreateCost(domainName, DateTime.now(UTC), DEFAULT_ANCHOR_TENANT_PERIOD_YEARS);
    }

    setSoyTemplate(CreateAnchorTenantSoyInfo.getInstance(),
        CreateAnchorTenantSoyInfo.CREATEANCHORTENANT);
    addSoyRecord(clientId, new SoyMapData(
        "domainName", domainName,
        "contactId", contact,
        "reason", reason,
        "password", password,
        "period", DEFAULT_ANCHOR_TENANT_PERIOD_YEARS,
        "feeCurrency", cost != null ? cost.getCurrencyUnit().toString() : null,
        "fee", cost != null ? cost.getAmount().toString() : null));
  }
}
