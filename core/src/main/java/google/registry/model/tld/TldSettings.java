// Copyright 2023 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use tldSettings file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.tld;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static com.google.common.collect.Ordering.natural;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.tld.Tld.TldState;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.persistence.VKey;
import google.registry.tldconfig.idn.IdnTableEnum;
import google.registry.util.Idn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.testcontainers.shaded.org.yaml.snakeyaml.Yaml;

/**
 * A POJO that can be used to convert {@link google.registry.model.tld.Tld} objects to YAML files
 * and YAML files to Tld object.
 */
public class TldSettings {

  public String tldStr;
  public String roidSuffix;
  public String pricingEngineClassName;
  public List<String> dnsWriters;
  public int numDnsPublishLocks;
  public String dnsAPlusAaaaTtl;
  public String dnsNsTtl;
  public String dnsDsTtl;
  public String tldUnicode;
  public String driveFolderId;
  public String tldType;
  public boolean invoicingEnabled;
  public Map<String, String> tldStateTransitions;
  public String creationTime;
  public List<String> reservedListNames;
  public String premiumListName;
  public boolean escrowEnabled;
  public boolean dnsPaused;
  public String addGracePeriodLength;
  public String anchorTenantAddGracePeriodLength;
  public String autoRenewGracePeriodLength;
  public String redemptionGracePeriodLength;
  public String renewGracePeriodLength;
  public String transferGracePeriodLength;
  public String automaticTransferLength;
  public String pendingDeleteLength;
  public String currency;
  public String createBillingCost;
  public String restoreBillingCost;
  public String serverStatusChangeBillingCost;
  public String registryLockOrUnlockBillingCost;
  public Map<String, String> renewBillingCostTransitions;
  public Map<String, String> eapFeeSchedule;
  public String lordnUsername;
  public String claimsPeriodEnd;
  public List<String> allowedRegistrantContactIds;
  public List<String> allowedFullyQualifiedHostNames;
  public List<String> defaultPromoTokens;
  public List<String> idnTables;

  /** Construct a {@link TldSettings} object from an existing {@link Tld} object. */
  public static TldSettings fromTld(Tld tld) {
    TldSettings tldSettings = new TldSettings();
    tldSettings.tldStr = tld.getTldStr();
    tldSettings.roidSuffix = tld.getRoidSuffix();
    tldSettings.pricingEngineClassName = tld.getPremiumPricingEngineClassName();
    tldSettings.dnsWriters = toNullableList(tld.getDnsWriters());
    tldSettings.numDnsPublishLocks = tld.getNumDnsPublishLocks();
    tldSettings.dnsAPlusAaaaTtl =
        tld.getDnsAPlusAaaaTtl().isPresent() ? tld.getDnsAPlusAaaaTtl().get().toString() : "";
    tldSettings.dnsNsTtl = tld.getDnsNsTtl().isPresent() ? tld.getDnsNsTtl().get().toString() : "";
    tldSettings.dnsDsTtl = tld.getDnsDsTtl().isPresent() ? tld.getDnsDsTtl().get().toString() : "";
    tldSettings.tldUnicode = tld.getTldUnicode();
    tldSettings.driveFolderId = tld.getDriveFolderId();
    tldSettings.tldType = tld.getTldType().name();
    tldSettings.invoicingEnabled = tld.getInvoicingEnabled();
    tldSettings.tldStateTransitions =
        tld.getTldStateTransitions().entrySet().stream()
            .collect(toMap(entry -> entry.getKey().toString(), entry -> entry.getValue().name()));
    tldSettings.creationTime =
        tld.getCreationTime() != null ? tld.getCreationTime().toString() : "";
    tldSettings.premiumListName = tld.getPremiumListName().orElse("");
    tldSettings.escrowEnabled = tld.getEscrowEnabled();
    tldSettings.dnsPaused = tld.getDnsPaused();
    tldSettings.addGracePeriodLength = tld.getAddGracePeriodLength().toString();
    tldSettings.anchorTenantAddGracePeriodLength =
        tld.getAnchorTenantAddGracePeriodLength().toString();
    tldSettings.autoRenewGracePeriodLength = tld.getAutoRenewGracePeriodLength().toString();
    tldSettings.redemptionGracePeriodLength = tld.getRedemptionGracePeriodLength().toString();
    tldSettings.renewGracePeriodLength = tld.getRenewGracePeriodLength().toString();
    tldSettings.transferGracePeriodLength = tld.getTransferGracePeriodLength().toString();
    tldSettings.automaticTransferLength = tld.getAutomaticTransferLength().toString();
    tldSettings.pendingDeleteLength = tld.getPendingDeleteLength().toString();
    tldSettings.currency = tld.getCurrency().toString();
    tldSettings.createBillingCost = tld.getStandardCreateCost().toString();
    tldSettings.restoreBillingCost = tld.getStandardRestoreCost().toString();
    tldSettings.serverStatusChangeBillingCost = tld.getServerStatusChangeCost().toString();
    tldSettings.registryLockOrUnlockBillingCost =
        tld.getRegistryLockOrUnlockBillingCost().toString();
    tldSettings.renewBillingCostTransitions =
        tld.getRenewBillingCostTransitions().entrySet().stream()
            .collect(
                toMap(entry -> entry.getKey().toString(), entry -> entry.getValue().toString()));
    tldSettings.eapFeeSchedule =
        tld.getEapFeeScheduleAsMap().entrySet().stream()
            .collect(
                toMap(entry -> entry.getKey().toString(), entry -> entry.getValue().toString()));
    tldSettings.lordnUsername = tld.getLordnUsername();
    tldSettings.claimsPeriodEnd = tld.getClaimsPeriodEnd().toString();

    // Not using the getters in order to preserve nullness
    tldSettings.allowedRegistrantContactIds = toNullableList(tld.allowedRegistrantContactIds);
    tldSettings.allowedFullyQualifiedHostNames = toNullableList(tld.allowedFullyQualifiedHostNames);
    tldSettings.reservedListNames = toNullableList(tld.reservedListNames);
    tldSettings.defaultPromoTokens =
        tld.defaultPromoTokens == null
            ? null
            : tld.defaultPromoTokens.stream()
                .map(vKey -> vKey.getKey().toString())
                .collect(toList());
    tldSettings.idnTables =
        tld.idnTables == null ? null : tld.idnTables.stream().map(Enum::toString).collect(toList());

    return tldSettings;
  }

  /** Construct a new {@link Tld} object from an existing {@link TldSettings} object. */
  public Tld toTld() {
    tm().assertInTransaction();
    // TODO(sarahbot@): Add a check in the new YAML update TLD command to ensure there are no
    // attempts to modify the creation time
    checkArgument(
        tldUnicode.equals(Idn.toUnicode(tldStr)),
        "tldUnicode must be the correct unicode-aware representation of the TLD");
    Tld existingTld = tm().loadByKey(Tld.createVKey(tldStr));
    checkArgument(
        existingTld != null,
        String.format(
            "The TLD %s must first be created before it can be modified using a YAML file",
            tldStr));
    PremiumList premiumList = null;
    if (!isNullOrEmpty(premiumListName)) {
      checkArgument(
          PremiumListDao.getLatestRevision(premiumListName).isPresent(),
          String.format("Premium list with name %s does not exist", premiumListName));
      premiumList = PremiumListDao.getLatestRevision(premiumListName).get();
    }
    return existingTld
        .asBuilder()
        .setTldStr(tldStr)
        .setRoidSuffix(roidSuffix)
        .setPremiumPricingEngine(pricingEngineClassName)
        .setDnsWriters(toNullableSet(dnsWriters))
        .setNumDnsPublishLocks(numDnsPublishLocks)
        .setDnsAPlusAaaaTtl(isNullOrEmpty(dnsAPlusAaaaTtl) ? null : Duration.parse(dnsAPlusAaaaTtl))
        .setDnsNsTtl(isNullOrEmpty(dnsNsTtl) ? null : Duration.parse(dnsNsTtl))
        .setDnsDsTtl(isNullOrEmpty(dnsDsTtl) ? null : Duration.parse(dnsDsTtl))
        .setDriveFolderId(driveFolderId)
        .setTldType(TldType.valueOf(tldType))
        .setInvoicingEnabled(invoicingEnabled)
        .setTldStateTransitions(
            tldStateTransitions.keySet().stream()
                .collect(
                    toImmutableSortedMap(
                        natural(),
                        DateTime::parse,
                        key -> TldState.valueOf(tldStateTransitions.get(key)))))
        .setNullableReservedListsByName(toNullableSet(reservedListNames))
        .setPremiumList(premiumList)
        .setEscrowEnabled(escrowEnabled)
        .setDnsPaused(dnsPaused)
        .setAddGracePeriodLength(Duration.parse(addGracePeriodLength))
        .setAnchorTenantAddGracePeriodLength(Duration.parse(anchorTenantAddGracePeriodLength))
        .setAutoRenewGracePeriodLength(Duration.parse(autoRenewGracePeriodLength))
        .setRedemptionGracePeriodLength(Duration.parse(redemptionGracePeriodLength))
        .setRenewGracePeriodLength(Duration.parse(renewGracePeriodLength))
        .setTransferGracePeriodLength(Duration.parse(transferGracePeriodLength))
        .setAutomaticTransferLength(Duration.parse(automaticTransferLength))
        .setPendingDeleteLength(Duration.parse(pendingDeleteLength))
        .setCurrency(CurrencyUnit.of(currency))
        .setCreateBillingCost(Money.parse(createBillingCost))
        .setRestoreBillingCost(Money.parse(restoreBillingCost))
        .setServerStatusChangeBillingCost(Money.parse(serverStatusChangeBillingCost))
        .setRegistryLockOrUnlockBillingCost(Money.parse(registryLockOrUnlockBillingCost))
        .setRenewBillingCostTransitions(
            renewBillingCostTransitions.keySet().stream()
                .collect(
                    toImmutableSortedMap(
                        natural(),
                        DateTime::parse,
                        key -> Money.parse(renewBillingCostTransitions.get(key)))))
        .setEapFeeSchedule(
            eapFeeSchedule.keySet().stream()
                .collect(
                    toImmutableSortedMap(
                        natural(), DateTime::parse, key -> Money.parse(eapFeeSchedule.get(key)))))
        .setLordnUsername(lordnUsername)
        .setClaimsPeriodEnd(DateTime.parse(claimsPeriodEnd))
        .setAllowedRegistrantContactIds(toNullableSet(allowedRegistrantContactIds))
        .setAllowedFullyQualifiedHostNames(toNullableSet(allowedFullyQualifiedHostNames))
        .setDefaultPromoTokens(
            defaultPromoTokens == null
                ? null
                : defaultPromoTokens.stream()
                    .map(token -> VKey.create(AllocationToken.class, token))
                    .collect(toImmutableList()))
        .setIdnTables(
            idnTables == null
                ? null
                : idnTables.stream().map(IdnTableEnum::valueOf).collect(toImmutableSet()))
        .build();
  }

  /** Convert an existing {@link Tld} object to a String in YAML format. */
  public static String toYaml(Tld tld) {
    return new Yaml().dumpAsMap(fromTld(tld));
  }

  /** Convert a YAML formatted String to {@link Tld} object. */
  public static Tld tldFromYaml(String yaml) {
    return new Yaml().loadAs(yaml, TldSettings.class).toTld();
  }

  private static ArrayList<String> toNullableList(@Nullable Set<String> set) {
    if (set == null) {
      return null;
    }
    return new ArrayList<>(set);
  }

  private static ImmutableSet<String> toNullableSet(@Nullable List<String> list) {
    if (list == null) {
      return null;
    }
    return ImmutableSet.copyOf(list);
  }
}
