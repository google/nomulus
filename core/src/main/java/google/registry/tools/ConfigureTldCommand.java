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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.tld.Tld.Builder.ROID_SUFFIX_PATTERN;
import static google.registry.model.tld.Tlds.getTlds;
import static google.registry.util.ListNamingUtils.convertFilePathToName;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.flogger.FluentLogger;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.tools.params.PathParameter;
import google.registry.util.Clock;
import google.registry.util.Idn;
import jakarta.inject.Inject;
import jakarta.inject.Named;
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
      names = {"-b", "--break_glass"},
      description =
          "Sets the breakGlass field on the TLD which prevents Cloud Build from overwriting new"
              + " TLD changes until the TLD configuration file stored internally matches the"
              + " configuration in the database. Setting this flag to false will indicate that"
              + " break glass mode should be turned off and any locally made changes to the"
              + " database should be overwritten by the internally stored configurations on the"
              + " next scheduled TLD sync.",
      arity = 1)
  Boolean breakGlass;

  @Parameter(
      names = {"--build_environment"},
      description =
          "DO NOT USE THIS FLAG ON THE COMMAND LINE! This flag indicates the command is being run"
              + " by the build environment tools. This flag should never be used by a human user"
              + " from the command line.")
  boolean buildEnv;

  @Parameter(
      names = {"-d", "--dry_run"},
      description = "Does not execute the entity mutation")
  boolean dryRun;

  @Inject ObjectMapper mapper;

  @Inject Clock clock;

  @Inject
  @Named("dnsWriterNames")
  Set<String> validDnsWriterNames;

  /** Indicates if the passed in file contains new changes to the TLD */
  boolean newDiff = true;

  /**
   * Indicates if the existing TLD is currently in break glass mode and should not be modified
   * unless the --break_glass flag is used
   */
  boolean oldTldInBreakGlass = false;

  @Override
  protected void init() throws Exception {
    if (RegistryToolEnvironment.get().equals(RegistryToolEnvironment.PRODUCTION)) {
      checkArgument(
          buildEnv || breakGlass != null,
          "Either the --break_glass or --build_environment flag must be used when"
              + " running the configure_tld command on Production");
    }
    String name = convertFilePathToName(inputFile);
    Map<String, Object> tldData = new Yaml().load(Files.newBufferedReader(inputFile));
    checkName(name, tldData);
    Tld oldTld = getTlds().contains(name) ? Tld.get(name) : null;

    checkForMissingFields(tldData.keySet().stream().collect(toImmutableSet()));
    Tld newTld = mapper.readValue(inputFile.toFile(), Tld.class);
    if (oldTld != null) {
      oldTldInBreakGlass = oldTld.getBreakglassMode();
      newDiff = !oldTld.equalYaml(newTld);
    }

    if (!newDiff && !oldTldInBreakGlass) {
      // Don't construct a new object if there is no new diff
      return;
    }

    checkArgument(
        oldTldInBreakGlass || !Boolean.FALSE.equals(breakGlass),
        "The --break_glass flag cannot be set to false to end break glass mode because the TLD is"
            + " not currently in break glass mode");

    if (oldTldInBreakGlass && !Boolean.TRUE.equals(breakGlass)) {
      checkArgument(
          !newDiff || breakGlass != null,
          "Changes can not be applied since TLD is in break glass mode but the --break_glass flag"
              + " was not used");
      // if there are no new diffs, then the YAML file has caught up to the database and the
      // break glass mode should be removed. Also remove the break glass mode if the break glass
      // flag was set to false.
      logger.atInfo().log("Break glass mode removed from TLD: %s", name);
    }

    checkPremiumList(newTld);
    checkDnsWriters(newTld);
    checkCurrency(newTld);
    // bsaEnrollStartTime only exists in DB. Need to carry it over to the updated copy. See Tld.java
    // for more information.
    Optional<DateTime> bsaEnrollTime =
        Optional.ofNullable(oldTld).flatMap(Tld::getBsaEnrollStartTime);
    if (bsaEnrollTime.isPresent()) {
      newTld = newTld.asBuilder().setBsaEnrollStartTime(bsaEnrollTime).build();
    }
    // Set the new TLD to break glass mode if break glass flag was used. Note that the break glass
    // mode does not need to be set to false if the --break_glass flag was set to false since the
    // field already defaults to false. Break glass mode will also automatically turn off if there
    // is no new diff and the --break_glass flag is null.
    if (Boolean.TRUE.equals(breakGlass)) {
      newTld = newTld.asBuilder().setBreakglassMode(true).build();
    }
    stageEntityChange(oldTld, newTld);
  }

  @Override
  protected boolean dontRunCommand() {
    if (dryRun) {
      return true;
    }
    if (!newDiff) {
      if (oldTldInBreakGlass && (breakGlass == null || !breakGlass)) {
        // Run command to remove break glass mode if there is no break glass flag or if the break
        // glass flag is false.
        return false;
      }
      logger.atInfo().log("TLD YAML file contains no new changes");
      checkArgument(
          breakGlass == null || oldTldInBreakGlass,
          "Break glass mode can only be set when making new changes to a TLD configuration");
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

  private void checkForMissingFields(ImmutableSet<String> keySet) {
    Set<String> tldFields =
        Arrays.stream(Tld.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .filter(field -> field.getAnnotation(JsonIgnore.class) == null)
            .map(Field::getName)
            .collect(Collectors.toSet());
    Set<String> missingFields = new HashSet<>();
    for (String field : tldFields) {
      if (!keySet.contains(field)) {
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
    if (premiumListName.isEmpty()) {
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
        currencyUnit.equals(newTld.getRestoreBillingCost().getCurrencyUnit()),
        "restoreBillingCost must use the same currency as the TLD");
    checkArgument(
        currencyUnit.equals(newTld.getServerStatusChangeBillingCost().getCurrencyUnit()),
        "serverStatusChangeBillingCost must use the same currency as the TLD");
    checkArgument(
        currencyUnit.equals(newTld.getRegistryLockOrUnlockBillingCost().getCurrencyUnit()),
        "registryLockOrUnlockBillingCost must use the same currency as the TLD");
    ImmutableSet<CurrencyUnit> currencies =
        newTld.getRenewBillingCostTransitions().values().stream()
            .map(Money::getCurrencyUnit)
            .distinct()
            .collect(toImmutableSet());
    checkArgument(
        currencies.size() == 1 && currencies.contains(currencyUnit),
        "All Money values in the renewBillingCostTransitions map must use the TLD's currency unit"
            + " %s. Found %s currency unit(s) in the renewBillingCostTransitionsMap",
        currencyUnit,
        currencies);
    ImmutableSet<CurrencyUnit> createCurrencies =
        newTld.getCreateBillingCostTransitions().values().stream()
            .map(Money::getCurrencyUnit)
            .distinct()
            .collect(toImmutableSet());
    checkArgument(
        createCurrencies.size() == 1 && createCurrencies.contains(currencyUnit),
        "All Money values in the createBillingCostTransitions map must use the TLD's currency unit"
            + " %s. Found %s currency unit(s) in the createBillingCostTransitionsMap",
        currencyUnit,
        createCurrencies);
    ImmutableSet<CurrencyUnit> eapCurrencies =
        newTld.getEapFeeScheduleAsMap().values().stream()
            .map(Money::getCurrencyUnit)
            .distinct()
            .collect(toImmutableSet());
    checkArgument(
        eapCurrencies.size() == 1 && eapCurrencies.contains(currencyUnit),
        "All Money values in the eapFeeSchedule map must use the TLD's currency unit"
            + " %s. Found %s currency unit(s) in the eapFeeSchedule",
        currencyUnit,
        eapCurrencies);
  }
}
