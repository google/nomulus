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

package google.registry.model.tld;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.util.Comparator.naturalOrder;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.tld.Tld.TldState;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.persistence.VKey;
import google.registry.tldconfig.idn.IdnTableEnum;
import google.registry.util.Idn;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

public class TldSettings {

  public String tldStr;
  public String roidSuffix;
  public String pricingEngineClassName;
  public Set<String> dnsWriters;
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
  public Set<String> reservedListNames;
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
  public Set<String> allowedRegistrantContactIds;
  public Set<String> allowedFullyQualifiedHostNames;
  public List<String> defaultPromoTokens;
  public Set<String> idnTables;

  public TldSettings fromTld(Tld tld) {
    this.tldStr = tld.getTldStr();
    this.roidSuffix = tld.getRoidSuffix();
    this.pricingEngineClassName = tld.getPremiumPricingEngineClassName();
    this.dnsWriters = tld.getDnsWriters();
    this.numDnsPublishLocks = tld.getNumDnsPublishLocks();
    this.dnsAPlusAaaaTtl =
        tld.getDnsAPlusAaaaTtl().isPresent() ? tld.getDnsAPlusAaaaTtl().get().toString() : "";
    this.dnsNsTtl = tld.getDnsNsTtl().isPresent() ? tld.getDnsNsTtl().get().toString() : "";
    this.dnsDsTtl = tld.getDnsDsTtl().isPresent() ? tld.getDnsDsTtl().get().toString() : "";
    this.tldUnicode = tld.getTldUnicode();
    this.driveFolderId = tld.getDriveFolderId();
    this.tldType = tld.getTldType().name();
    this.invoicingEnabled = tld.getInvoicingEnabled();
    this.tldStateTransitions =
        tld.getTldStateTransitions().entrySet().stream()
            .collect(toMap(entry -> entry.getKey().toString(), entry -> entry.getValue().name()));
    this.creationTime = tld.getCreationTime() != null ? tld.getCreationTime().toString() : "";
    this.reservedListNames = tld.getReservedListNames();
    this.premiumListName = tld.getPremiumListName().orElse("");
    this.escrowEnabled = tld.getEscrowEnabled();
    this.dnsPaused = tld.getDnsPaused();
    this.addGracePeriodLength = tld.getAddGracePeriodLength().toString();
    this.anchorTenantAddGracePeriodLength = tld.getAnchorTenantAddGracePeriodLength().toString();
    this.autoRenewGracePeriodLength = tld.getAutoRenewGracePeriodLength().toString();
    this.redemptionGracePeriodLength = tld.getRedemptionGracePeriodLength().toString();
    this.renewGracePeriodLength = tld.getRenewGracePeriodLength().toString();
    this.transferGracePeriodLength = tld.getTransferGracePeriodLength().toString();
    this.automaticTransferLength = tld.getAutomaticTransferLength().toString();
    this.pendingDeleteLength = tld.getPendingDeleteLength().toString();
    this.currency = tld.getCurrency().toString();
    this.createBillingCost = tld.getStandardCreateCost().toString();
    this.restoreBillingCost = tld.getStandardRestoreCost().toString();
    this.serverStatusChangeBillingCost = tld.getServerStatusChangeCost().toString();
    this.registryLockOrUnlockBillingCost = tld.getRegistryLockOrUnlockBillingCost().toString();
    this.renewBillingCostTransitions =
        tld.getRenewBillingCostTransitions().entrySet().stream()
            .collect(
                toMap(entry -> entry.getKey().toString(), entry -> entry.getValue().toString()));
    this.eapFeeSchedule =
        tld.getEapFeeScheduleAsMap().entrySet().stream()
            .collect(
                toMap(entry -> entry.getKey().toString(), entry -> entry.getValue().toString()));
    this.lordnUsername = tld.getLordnUsername();
    this.claimsPeriodEnd = tld.getClaimsPeriodEnd().toString();
    this.allowedRegistrantContactIds = tld.getAllowedRegistrantContactIds();
    this.allowedFullyQualifiedHostNames = tld.getAllowedFullyQualifiedHostNames();
    this.defaultPromoTokens =
        tld.getDefaultPromoTokens().stream()
            .map(vKey -> vKey.getKey().toString())
            .collect(toList());
    this.idnTables = tld.getIdnTables().stream().map(Enum::toString).collect(toSet());
    return this;
  }

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
        .setDnsWriters(ImmutableSet.copyOf(dnsWriters))
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
                        naturalOrder(),
                        DateTime::parse,
                        key -> TldState.valueOf(tldStateTransitions.get(key)))))
        .setReservedListsByName(reservedListNames)
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
            (ImmutableSortedMap<DateTime, Money>)
                renewBillingCostTransitions.keySet().stream()
                    .collect(
                        toImmutableMap(
                            DateTime::parse,
                            key -> Money.parse(renewBillingCostTransitions.get(key)))))
        .setEapFeeSchedule(
            (ImmutableSortedMap<DateTime, Money>)
                eapFeeSchedule.keySet().stream()
                    .collect(
                        toImmutableMap(
                            DateTime::parse, key -> Money.parse(eapFeeSchedule.get(key)))))
        .setLordnUsername(lordnUsername)
        .setClaimsPeriodEnd(DateTime.parse(claimsPeriodEnd))
        .setAllowedRegistrantContactIds(ImmutableSet.copyOf(allowedRegistrantContactIds))
        .setAllowedFullyQualifiedHostNames(ImmutableSet.copyOf(allowedFullyQualifiedHostNames))
        .setDefaultPromoTokens(
            defaultPromoTokens.stream()
                .map(token -> VKey.create(AllocationToken.class, token))
                .collect(toImmutableList()))
        .setIdnTables(idnTables.stream().map(IdnTableEnum::valueOf).collect(toImmutableSet()))
        .build();
  }
}
