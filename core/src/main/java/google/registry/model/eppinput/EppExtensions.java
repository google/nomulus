// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.eppinput;

import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName.CREATE;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeExtensionCommandDescriptor;
import google.registry.model.domain.fee06.FeeCheckCommandExtensionItemV06;
import google.registry.model.domain.fee06.FeeCheckCommandExtensionV06;
import google.registry.model.domain.fee06.FeeCreateCommandExtensionV06;
import google.registry.model.domain.fee12.FeeCreateCommandExtensionV12;
import google.registry.model.domain.launch.LaunchCheckExtension;
import google.registry.model.domain.launch.LaunchCheckExtension.CheckType;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.domain.secdns.SecDnsUpdateExtension.Add;
import google.registry.model.domain.secdns.SecDnsUpdateExtension.Remove;
import google.registry.model.domain.superuser.DomainDeleteSuperuserExtension;
import google.registry.model.domain.superuser.DomainUpdateSuperuserExtension;
import google.registry.model.domain.token.AllocationTokenExtension;
import java.math.BigDecimal;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;

/** Static helpers for creating common EPP extensions. */
public class EppExtensions {

  /**
   * Returns a metadata extension with the specified reason and flags.
   *
   * @param reason the reason for the change, recorded in history entries
   * @param requestedByRegistrar whether the change was requested by a registrar
   * @param isAnchorTenant whether the domain is an anchor tenant
   */
  @Nullable
  public static MetadataExtension metadata(
      @Nullable String reason,
      @Nullable Boolean requestedByRegistrar,
      @Nullable Boolean isAnchorTenant) {
    if (isNullOrEmpty(reason) && requestedByRegistrar == null && isAnchorTenant == null) {
      return null;
    }
    return new MetadataExtension.Builder()
        .setReason(reason)
        .setRequestedByRegistrar(requestedByRegistrar)
        .setAnchorTenant(isAnchorTenant)
        .build();
  }

  /** Returns a metadata extension for standard tool commands. */
  @Nullable
  public static MetadataExtension toolMetadata(
      @Nullable String reason, @Nullable Boolean requestedByRegistrar) {
    return metadata(reason, requestedByRegistrar, null);
  }

  /** Returns an allocation token extension for the specified token string. */
  @Nullable
  public static AllocationTokenExtension allocationToken(@Nullable String token) {
    return isNullOrEmpty(token) ? null : AllocationTokenExtension.create(token);
  }

  /** Returns a domain update superuser extension with the specified autorenew flag. */
  @Nullable
  public static DomainUpdateSuperuserExtension updateSuperuser(@Nullable Boolean autorenews) {
    return autorenews == null ? null : DomainUpdateSuperuserExtension.create(autorenews);
  }

  /** Returns a domain delete superuser extension for immediate deletion if requested. */
  @Nullable
  public static DomainDeleteSuperuserExtension deleteSuperuser(boolean immediately) {
    return immediately ? DomainDeleteSuperuserExtension.create(0, 0) : null;
  }

  /** Returns a fee create extension (V12) for a single fee. */
  @Nullable
  public static FeeCreateCommandExtensionV12 feeCreate(@Nullable Money cost) {
    return cost == null ? null : feeCreate(cost.getCurrencyUnit(), cost.getAmount());
  }

  /** Returns a fee create extension (V12) for a single fee with a simple currency and cost. */
  @Nullable
  public static FeeCreateCommandExtensionV12 feeCreate(
      @Nullable CurrencyUnit currency, @Nullable BigDecimal cost) {
    if (currency == null || cost == null) {
      return null;
    }
    return new FeeCreateCommandExtensionV12.Builder()
        .setCurrency(currency)
        .setFees(ImmutableList.of(new Fee.Builder().setCost(cost).build()))
        .build();
  }

  /** Returns a fee create extension (V06) for a single fee. */
  @Nullable
  public static FeeCreateCommandExtensionV06 feeCreateV06(@Nullable Money cost) {
    if (cost == null) {
      return null;
    }
    return new FeeCreateCommandExtensionV06.Builder()
        .setCurrency(cost.getCurrencyUnit())
        .setFees(ImmutableList.of(new Fee.Builder().setCost(cost.getAmount()).build()))
        .build();
  }

  /** Returns a secDNS create extension with the specified DS records. */
  @Nullable
  public static SecDnsCreateExtension secDnsCreate(ImmutableSet<DomainDsData> dsData) {
    if (dsData.isEmpty()) {
      return null;
    }
    return new SecDnsCreateExtension.Builder().setDsData(dsData).build();
  }

  /** Returns a secDNS update extension to replace or modify DS records. */
  @Nullable
  public static SecDnsUpdateExtension secDnsUpdate(
      ImmutableSet<DomainDsData> add, ImmutableSet<DomainDsData> remove, boolean removeAll) {
    if (add.isEmpty() && remove.isEmpty() && !removeAll) {
      return null;
    }
    SecDnsUpdateExtension.Builder builder = new SecDnsUpdateExtension.Builder();
    if (removeAll) {
      builder.setRemove(new Remove.Builder().setAll(true).build());
    } else if (!remove.isEmpty()) {
      builder.setRemove(new Remove.Builder().setDsData(remove).build());
    }
    if (!add.isEmpty()) {
      builder.setAdd(new Add.Builder().setDsData(add).build());
    }
    return builder.build();
  }

  /** Returns a fee check extension for domain creations (V06). */
  public static FeeCheckCommandExtensionV06 feeCheckCreateV06(ImmutableList<String> domainNames) {
    return feeCheckCreateV06(domainNames, 1);
  }

  /** Returns a fee check extension for domain creations (V06) with a specific period. */
  public static FeeCheckCommandExtensionV06 feeCheckCreateV06(
      ImmutableList<String> domainNames, int years) {
    FeeCheckCommandExtensionV06 feeCheck = new FeeCheckCommandExtensionV06();
    ImmutableList.Builder<FeeCheckCommandExtensionItemV06> items = new ImmutableList.Builder<>();
    for (String domainName : domainNames) {
      items.add(
          FeeCheckCommandExtensionItemV06.create(
              domainName,
              null,
              FeeExtensionCommandDescriptor.create(CREATE, null, null),
              Period.create(years, Period.Unit.YEARS)));
    }
    feeCheck.setItems(items.build());
    return feeCheck;
  }

  /** Returns a launch check extension for claims. */
  public static LaunchCheckExtension launchCheckClaims() {
    return LaunchCheckExtension.create(CheckType.CLAIMS, LaunchPhase.CLAIMS);
  }
}
