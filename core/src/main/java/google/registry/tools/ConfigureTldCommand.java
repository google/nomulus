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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.tld.Tld.Builder.ROID_SUFFIX_PATTERN;
import static google.registry.model.tld.Tlds.getTlds;
import static google.registry.util.ListNamingUtils.convertFilePathToName;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.flogger.FluentLogger;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.tools.params.PathParameter;
import google.registry.util.Idn;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Parameter(
      names = {"-i", "--input"},
      description = "Filename of TLD YAML file.",
      validateWith = PathParameter.InputFile.class,
      required = true)
  Path inputFile;

  @Parameter(
      names = {"-b", "--breakglass"},
      description =
          "Sets the breakglass field on the TLD to true, preventing Cloud Build from overwriting"
              + " these new changes until the TLD configuration file stored internally matches the"
              + " configuration in the database.")
  boolean breakglass;

  @Parameter(
      names = {"-d", "--dryrun"},
      description = "Does not execute the entity mutation")
  boolean dryrun;

  @Inject ObjectMapper mapper;

  @Inject
  @Named("dnsWriterNames")
  Set<String> validDnsWriterNames;

  /** Indicates if the passed in file contains new changes to the TLD */
  boolean newDiff = true;

  /**
   * Indicates if the existing TLD is currently in breakglass mode and should not be modified unless
   * the breakglass flag is used
   */
  boolean oldTldInBreakglass = false;

  @Override
  protected void init() throws Exception {
    String name = convertFilePathToName(inputFile);
    Map<String, Object> tldData = new Yaml().load(Files.newBufferedReader(inputFile));
    checkName(name, tldData);
    checkForMissingFields(tldData);
    Tld oldTld = getTlds().contains(name) ? Tld.get(name) : null;
    Tld newTld = mapper.readValue(inputFile.toFile(), Tld.class);
    if (oldTld != null) {
      oldTldInBreakglass = oldTld.getBreakglassMode();
      newDiff = !oldTld.equalYaml(newTld);
    }

    if (!newDiff && !oldTldInBreakglass) {
      // Don't construct a new object if there is no new diff
      return;
    }

    if (oldTldInBreakglass && !breakglass) {
      checkArgument(
          !newDiff,
          "Changes can not be applied since TLD is in breakglass mode but the breakglass flag was"
              + " not used");
      // if there are no new diffs, then the YAML file has caught up to the database and the
      // breakglass mode should be removed
      logger.atInfo().log("Breakglass mode removed from TLD: %s", name);
    }

    checkPremiumList(newTld);
    checkDnsWriters(newTld);
    checkCurrency(newTld);
    // Set the new TLD to breakglass mode if breakglass flag was used
    if (breakglass) {
      newTld = newTld.asBuilder().setBreakglassMode(true).build();
    }
    stageEntityChange(oldTld, newTld);
  }

  @Override
  protected boolean dontRunCommand() {
    if (dryrun) {
      return true;
    }
    if (!newDiff) {
      if (oldTldInBreakglass && !breakglass) {
        // Run command to remove breakglass mode
        return false;
      }
      logger.atInfo().log("TLD YAML file contains no new changes");
      checkArgument(
          !breakglass || oldTldInBreakglass,
          "Breakglass mode can only be set when making new changes to a TLD configuration");
      return true;
    }
    return false;
  }

  private void checkName(String name, Map<String, Object> tldData) {
    checkArgument(CharMatcher.ascii().matchesAllOf(name), "A TLD name must be in plain ASCII");
    checkArgument(!Character.isDigit(name.charAt(0)), "TLDs cannot begin with a number");
    checkArgument(
        tldData.get("tldStr").equals(name),
        "The input file name must match the name of the TLD it represents");
    checkArgument(
        tldData.get("tldUnicode").equals(Idn.toUnicode(name)),
        "The value for tldUnicode must equal the unicode representation of the TLD name");
    checkArgument(
        ROID_SUFFIX_PATTERN.matcher((CharSequence) tldData.get("roidSuffix")).matches(),
        "ROID suffix must be in format %s",
        ROID_SUFFIX_PATTERN.pattern());
  }

  private void checkForMissingFields(Map<String, Object> tldData) {
    Set<String> tldFields =
        Arrays.stream(Tld.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .filter(field -> field.getAnnotation(JsonIgnore.class) == null)
            .map(Field::getName)
            .collect(Collectors.toSet());
    Set<String> missingFields = new HashSet<>();
    for (String field : tldFields) {
      if (!tldData.containsKey(field)) {
        missingFields.add(field);
      }
    }
    checkArgument(
        missingFields.isEmpty(),
        "The input file is missing data for the following fields: %s",
        missingFields);
  }

  private void checkPremiumList(Tld newTld) {
    Optional<String> premiumListName = newTld.getPremiumListName();
    if (!premiumListName.isPresent()) {
      return;
    }
    Optional<PremiumList> premiumList = PremiumListDao.getLatestRevision(premiumListName.get());
    checkArgument(
        premiumList.isPresent(),
        "The premium list with the name %s does not exist",
        premiumListName.get());
    checkArgument(
        premiumList.get().getCurrency().equals(newTld.getCurrency()),
        "The premium list must use the TLD's currency");
  }

  private void checkDnsWriters(Tld newTld) {
    ImmutableSet<String> dnsWriters = newTld.getDnsWriters();
    SetView<String> invalidDnsWriters = Sets.difference(dnsWriters, validDnsWriterNames);
    checkArgument(
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
