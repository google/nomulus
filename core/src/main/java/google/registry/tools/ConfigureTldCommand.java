// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.flogger.util.Checks.checkArgument;
import static google.registry.model.tld.Tlds.getTlds;
import static google.registry.util.ListNamingUtils.convertFilePathToName;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.tools.params.PathParameter;
import google.registry.util.Idn;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.yaml.snakeyaml.Yaml;

/** Command to create or update a {@link Tld} using a YAML file. */
@Parameters(separators = " =", commandDescription = "Create or update TLD using YAML")
public class ConfigureTldCommand extends MutatingCommand {

  @Parameter(
      names = {"-i", "--input"},
      description = "Filename of TLD YAML file.",
      validateWith = PathParameter.InputFile.class,
      required = true)
  Path inputFile;

  @Inject ObjectMapper mapper;

  @Inject
  @Named("dnsWriterNames")
  Set<String> validDnsWriterNames;

  // TODO(sarahbot@): Add a breakglass setting to this tool to indicate when a TLD has been modified
  // outside of source control

  // TODO(sarahbot@): Add a check for diffs between passed in file and current TLD and exit if there
  // is no diff. Treat nulls and empty sets as the same value.

  @Override
  protected void init() throws Exception {
    String name = convertFilePathToName(inputFile);
    Map<String, Object> tldData = new Yaml().load(Files.newBufferedReader(inputFile));
    checkForMissingFields(tldData);
    checkArgument(!Character.isDigit(name.charAt(0)), "TLDs cannot begin with a number");
    checkArgument(
        tldData.get("tldStr").equals(name),
        "The input file name must match the name of the TLD it represents");
    checkArgument(
        tldData.get("tldUnicode").equals(Idn.toUnicode(name)),
        "The value for tldUnicode must equal the unicode representation of the TLD name");
    Tld oldTld = getTlds().contains(name) ? Tld.get(name) : null;
    Tld newTld = mapper.readValue(inputFile.toFile(), Tld.class);
    checkPremiumList(newTld);
    checkDnsWriters(newTld);
    checkCurrency(newTld);
    stageEntityChange(oldTld, newTld);
  }

  private void checkForMissingFields(Map<String, Object> tldData) {
    Set<String> tldFields =
        Arrays.stream(Tld.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());
    tldFields.removeAll(ImmutableSet.of("breakglassMode", "$jacocoData", "CACHE"));
    Set<String> missingFields = new HashSet<>();
    for (String field : tldFields) {
      if (!field.startsWith("DEFAULT") && !tldData.keySet().contains(field)) {
        missingFields.add(field);
      }
    }
    checkArgument(
        missingFields.isEmpty(),
        String.format(
            "The input file is missing data for the following fields: %s", missingFields));
  }

  private void checkPremiumList(Tld newTld) {
    Optional<String> premiumListName = newTld.getPremiumListName();
    if (!premiumListName.isPresent()) return;
    Optional<PremiumList> premiumList = PremiumListDao.getLatestRevision(premiumListName.get());
    checkArgument(
        premiumList.isPresent(),
        String.format("The premium list with the name %s does not exist", premiumListName.get()));
    checkArgument(
        premiumList.get().getCurrency().equals(newTld.getCurrency()),
        "The premium list must use the TLD's currency");
  }

  private void checkDnsWriters(Tld newTld) {
    ImmutableSet<String> dnsWriters = newTld.getDnsWriters();
    ImmutableSortedSet<String> invalidDnsWriters =
        ImmutableSortedSet.copyOf(Sets.difference(dnsWriters, validDnsWriterNames));
    Preconditions.checkArgument(
        invalidDnsWriters.isEmpty(), "Invalid DNS writer name(s) specified: %s", invalidDnsWriters);
  }

  private void checkCurrency(Tld newTld) {
    CurrencyUnit currencyUnit = newTld.getCurrency();
    checkArgument(
        currencyUnit.equals(newTld.getCreateBillingCost().getCurrencyUnit()),
        "createBillingCost must use the same currency as the TLD");
    checkArgument(
        currencyUnit.equals(newTld.getRestoreBillingCost().getCurrencyUnit()),
        "restoreBillingCost must use the same currency as the TLD");
    checkArgument(
        currencyUnit.equals(newTld.getServerStatusChangeBillingCost().getCurrencyUnit()),
        "serverStatusChangeBillingCost must use the same currency as the TLD");
    checkArgument(
        currencyUnit.equals(newTld.getRegistryLockOrUnlockBillingCost().getCurrencyUnit()),
        "registryLockOrUnlockBillingCost must use the same currency as the TLD");
    ImmutableSortedMap<DateTime, Money> renewBillingCostTransitions =
        newTld.getRenewBillingCostTransitions();
    for (Money renewBillingCost : renewBillingCostTransitions.values()) {
      checkArgument(
          renewBillingCost.getCurrencyUnit().equals(currencyUnit),
          "All Money values in the renewBillingCostTransitions map must use the TLD's currency"
              + " unit");
    }
    ImmutableSortedMap<DateTime, Money> eapFeeSchedule = newTld.getEapFeeScheduleAsMap();
    for (Money eapFee : eapFeeSchedule.values()) {
      checkArgument(
          eapFee.getCurrencyUnit().equals(currencyUnit),
          "All Money values in the eapFeeSchedule map must use the TLD's currency unit");
    }
  }
}
