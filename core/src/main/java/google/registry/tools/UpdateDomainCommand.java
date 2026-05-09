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
import static google.registry.model.domain.rgp.GracePeriodStatus.AUTO_RENEW;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static java.util.function.Predicate.isEqual;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import google.registry.flows.ResourceFlowUtils;
import google.registry.model.domain.Domain;
import google.registry.model.domain.GracePeriodBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppExtensions;
import google.registry.model.eppinput.EppInputs;
import google.registry.model.eppinput.EppInputs.DomainUpdateBuilder;
import google.registry.tools.params.NameserversParameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;

/** A command to update a new domain via EPP. */
@Parameters(separators = " =", commandDescription = "Update a new domain via EPP.")
final class UpdateDomainCommand extends CreateOrUpdateDomainCommand {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Parameter(names = "--statuses", description = "Comma-separated list of statuses to set.")
  private List<String> statuses = new ArrayList<>();

  @Parameter(
      names = "--add_nameservers",
      description =
          "Comma-delimited list of nameservers to add, up to 13. "
              + "Cannot be set if --nameservers is set.",
      listConverter = NameserversParameter.class,
      validateWith = NameserversParameter.class)
  private Set<String> addNameservers = new HashSet<>();

  @Parameter(
    names = "--add_statuses",
    description = "Statuses to add. Cannot be set if --statuses is set."
  )
  private List<String> addStatuses = new ArrayList<>();

  @Parameter(
      names = "--add_ds_records",
      description =
          "DS records to add. Cannot be set if --ds_records or --clear_ds_records is set.",
      converter = DsRecord.Converter.class)
  private List<DsRecord> addDsRecords = new ArrayList<>();

  @Parameter(
      names = "--remove_nameservers",
      description =
          "Comma-delimited list of nameservers to remove, up to 13. "
              + "Cannot be set if --nameservers is set.",
      listConverter = NameserversParameter.class,
      validateWith = NameserversParameter.class)
  private Set<String> removeNameservers = new HashSet<>();

  @Parameter(
    names = "--remove_statuses",
    description = "Statuses to remove. Cannot be set if --statuses is set."
  )
  private List<String> removeStatuses = new ArrayList<>();

  @Parameter(
      names = "--remove_ds_records",
      description =
          "DS records to remove. Cannot be set if --ds_records or --clear_ds_records is set.",
      converter = DsRecord.Converter.class)
  private List<DsRecord> removeDsRecords = new ArrayList<>();

  @Parameter(
    names = "--clear_ds_records",
    description =
        "removes all DS records. Is implied true if --ds_records is set."
  )
  boolean clearDsRecords = false;

  @Nullable
  @Parameter(
      names = "--autorenews",
      arity = 1,
      description =
          "Whether the domain autorenews. If false, the domain will automatically be"
              + " deleted at the end of its current registration period.")
  Boolean autorenews;

  @Parameter(
      names = {"--force_in_pending_delete"},
      description = "Force a superuser update even on domains that are in pending delete")
  boolean forceInPendingDelete;

  @Override
  protected void initMutatingEppToolCommand()
      throws ResourceFlowUtils.ResourceDoesNotExistException {
    if (!nameservers.isEmpty()) {
      checkArgument(
          addNameservers.isEmpty() && removeNameservers.isEmpty(),
          "If you provide the nameservers flag, "
              + "you cannot use the add_nameservers and remove_nameservers flags.");
    } else {
      checkArgument(addNameservers.size() <= 13, "You can add at most 13 nameservers.");
    }
    if (!statuses.isEmpty()) {
      checkArgument(
          addStatuses.isEmpty() && removeStatuses.isEmpty(),
          "If you provide the statuses flag, "
              + "you cannot use the add_statuses and remove_statuses flags.");
    }

    if (!dsRecords.isEmpty() || clearDsRecords){
      checkArgument(
          addDsRecords.isEmpty() && removeDsRecords.isEmpty(),
          "If you provide the ds_records or clear_ds_records flags, "
              + "you cannot use the add_ds_records and remove_ds_records flags.");
      addDsRecords = dsRecords;
      clearDsRecords = true;
    }

    ImmutableSet.Builder<String> autorenewGracePeriodWarningDomains = new ImmutableSet.Builder<>();
    Instant now = clock.now();
    for (String domainName : domains) {
      Domain domain = ResourceFlowUtils.loadAndVerifyExistence(Domain.class, domainName, now);
      checkArgument(
          !domain.getStatusValues().contains(SERVER_UPDATE_PROHIBITED),
          "The domain '%s' has status SERVER_UPDATE_PROHIBITED. Verify that you are allowed "
              + "to make updates, and if so, use the unlock_domain command to enable updates.",
          domainName);
      checkArgument(
          !domain.getStatusValues().contains(PENDING_DELETE) || forceInPendingDelete,
          "The domain '%s' has status PENDING_DELETE. Verify that you really are intending to "
              + "update a domain in pending delete (this is uncommon), and if so, pass the "
              + "--force_in_pending_delete parameter to allow this update.",
          domainName);

      // Use TreeSets so that the results are always in the same order (this makes testing easier).
      Set<String> addNameserversThisDomain = new TreeSet<>(addNameservers);
      Set<String> removeNameserversThisDomain = new TreeSet<>(removeNameservers);
      Set<String> addStatusesThisDomain = new TreeSet<>(addStatuses);
      Set<String> removeStatusesThisDomain = new TreeSet<>(removeStatuses);

      if (!nameservers.isEmpty() || !statuses.isEmpty()) {
        if (!nameservers.isEmpty()) {
          ImmutableSortedSet<String> existingNameservers = domain.loadNameserverHostNames();
          ImmutableSet<String> targetNameservers = ImmutableSet.copyOf(nameservers);
          addNameserversThisDomain =
              new TreeSet<>(Sets.difference(targetNameservers, existingNameservers));
          removeNameserversThisDomain =
              new TreeSet<>(Sets.difference(existingNameservers, targetNameservers));

          int numNameservers =
              existingNameservers.size()
                  + addNameserversThisDomain.size()
                  - removeNameserversThisDomain.size();
          checkArgument(
              numNameservers <= 13,
              "The resulting nameservers count for domain %s would be more than 13",
              domainName);
        }

        if (!statuses.isEmpty()) {
          ImmutableSet<String> currentStatusValues =
              domain.getStatusValues().stream()
                  .map(StatusValue::getXmlName)
                  .collect(ImmutableSet.toImmutableSet());
          ImmutableSet<String> targetStatuses = ImmutableSet.copyOf(statuses);
          addStatusesThisDomain =
              new TreeSet<>(Sets.difference(targetStatuses, currentStatusValues));
          removeStatusesThisDomain =
              new TreeSet<>(Sets.difference(currentStatusValues, targetStatuses));
        }
      }

      boolean add =
          (!addNameserversThisDomain.isEmpty()
              || !addStatusesThisDomain.isEmpty());

      boolean remove =
          (!removeNameserversThisDomain.isEmpty()
              || !removeStatusesThisDomain.isEmpty());

      boolean change = password != null;
      boolean secDns =
          (!addDsRecords.isEmpty()
              || !removeDsRecords.isEmpty()
              || !dsRecords.isEmpty()
              || clearDsRecords);

      if (!add && !remove && !change && !secDns && autorenews == null) {
        logger.atInfo().log("No changes need to be made to domain '%s'.", domainName);
        continue;
      }

      // If autorenew is being turned off and this domain is already in the autorenew grace period,
      // then we want to warn the user that they might want to delete it instead.
      if (Boolean.FALSE.equals(autorenews)) {
        if (domain.getGracePeriods().stream()
            .map(GracePeriodBase::getType)
            .anyMatch(isEqual(AUTO_RENEW))) {
          autorenewGracePeriodWarningDomains.add(domainName);
        }
      }

      DomainUpdateBuilder updateBuilder = EppInputs.updateDomain(domainName);

      if (secDns) {
        updateBuilder.setSecDnsUpdate(
            EppExtensions.secDnsUpdate(
                addDsRecords.stream()
                    .map(DsRecord::toDsData)
                    .collect(ImmutableSet.toImmutableSet()),
                removeDsRecords.stream()
                    .map(DsRecord::toDsData)
                    .collect(ImmutableSet.toImmutableSet()),
                clearDsRecords));
      }

      updateBuilder.setAutorenews(autorenews);
      updateBuilder.addExtension(EppExtensions.toolMetadata(reason, requestedByRegistrar));

      if (add) {
        updateBuilder.addNameservers(ImmutableSet.copyOf(addNameserversThisDomain));
        updateBuilder.addStatuses(
            addStatusesThisDomain.stream()
                .map(StatusValue::fromXmlName)
                .collect(ImmutableSet.toImmutableSet()));
      }

      if (remove) {
        updateBuilder.removeNameservers(ImmutableSet.copyOf(removeNameserversThisDomain));
        updateBuilder.removeStatuses(
            removeStatusesThisDomain.stream()
                .map(StatusValue::fromXmlName)
                .collect(ImmutableSet.toImmutableSet()));
      }

      if (change) {
        updateBuilder.setNewPassword(password);
      }

      addEppInput(clientId, updateBuilder.build());
    }

    ImmutableSet<String> domainsToWarn = autorenewGracePeriodWarningDomains.build();
    if (!domainsToWarn.isEmpty()) {
      logger.atWarning().log(
          "The following domains are in autorenew grace periods. Consider aborting this command"
              + " and running `nomulus delete_domain` instead to terminate autorenewal immediately"
              + " rather than in one year, if desired:\n%s",
          String.join(", ", domainsToWarn));
    }
  }
}
