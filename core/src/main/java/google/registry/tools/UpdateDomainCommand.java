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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.domain.rgp.GracePeriodStatus.AUTO_RENEW;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static java.util.function.Predicate.isEqual;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.template.soy.data.SoyMapData;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriodBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.tools.params.NameserversParameter;
import google.registry.tools.soy.DomainUpdateSoyInfo;
import google.registry.util.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** A command to update a new domain via EPP. */
@Parameters(separators = " =", commandDescription = "Update a new domain via EPP.")
final class UpdateDomainCommand extends CreateOrUpdateDomainCommand {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;

  @Parameter(names = "--statuses", description = "Comma-separated list of statuses to set.")
  private List<String> statuses = new ArrayList<>();

  @Parameter(
      names = "--add_nameservers",
      description =
          "Comma-delimited list of nameservers to add, up to 13. "
              + "Cannot be set if --nameservers is set.",
      converter = NameserversParameter.class,
      validateWith = NameserversParameter.class)
  private Set<String> addNameservers = new HashSet<>();

  @Parameter(
    names = "--add_admins",
    description = "Admins to add. Cannot be set if --admins is set."
  )
  private List<String> addAdmins = new ArrayList<>();

  @Parameter(names = "--add_techs", description = "Techs to add. Cannot be set if --techs is set.")
  private List<String> addTechs = new ArrayList<>();

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
      converter = NameserversParameter.class,
      validateWith = NameserversParameter.class)
  private Set<String> removeNameservers = new HashSet<>();

  @Parameter(
    names = "--remove_admins",
    description = "Admins to remove. Cannot be set if --admins is set."
  )
  private List<String> removeAdmins = new ArrayList<>();

  @Parameter(
    names = "--remove_techs",
    description = "Techs to remove. Cannot be set if --techs is set."
  )
  private List<String> removeTechs = new ArrayList<>();

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
  protected void initMutatingEppToolCommand() {
    if (!nameservers.isEmpty()) {
      checkArgument(
          addNameservers.isEmpty() && removeNameservers.isEmpty(),
          "If you provide the nameservers flag, "
              + "you cannot use the add_nameservers and remove_nameservers flags.");
    } else {
      checkArgument(addNameservers.size() <= 13, "You can add at most 13 nameservers.");
    }
    if (!admins.isEmpty()) {
      checkArgument(
          addAdmins.isEmpty() && removeAdmins.isEmpty(),
          "If you provide the admins flag, you cannot use the add_admins and remove_admins flags.");
    }
    if (!techs.isEmpty()) {
      checkArgument(
          addTechs.isEmpty() && removeTechs.isEmpty(),
          "If you provide the techs flag, you cannot use the add_techs and remove_techs flags.");
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
    DateTime now = clock.nowUtc();
    for (String domain : domains) {
      Optional<DomainBase> domainOptional = loadByForeignKey(DomainBase.class, domain, now);
      checkArgumentPresent(domainOptional, "Domain '%s' does not exist or is deleted", domain);
      DomainBase domainBase = domainOptional.get();
      checkArgument(
          !domainBase.getStatusValues().contains(SERVER_UPDATE_PROHIBITED),
          "The domain '%s' has status SERVER_UPDATE_PROHIBITED. Verify that you are allowed "
              + "to make updates, and if so, use the domain_unlock command to enable updates.",
          domain);
      checkArgument(
          !domainBase.getStatusValues().contains(PENDING_DELETE) || forceInPendingDelete,
          "The domain '%s' has status PENDING_DELETE. Verify that you really are intending to "
              + "update a domain in pending delete (this is uncommon), and if so, pass the "
              + "--force_in_pending_delete parameter to allow this update.",
          domain);

      // Use TreeSets so that the results are always in the same order (this makes testing easier).
      Set<String> addAdminsThisDomain = new TreeSet<>(addAdmins);
      Set<String> removeAdminsThisDomain = new TreeSet<>(removeAdmins);
      Set<String> addTechsThisDomain = new TreeSet<>(addTechs);
      Set<String> removeTechsThisDomain = new TreeSet<>(removeTechs);
      Set<String> addNameserversThisDomain = new TreeSet<>(addNameservers);
      Set<String> removeNameserversThisDomain = new TreeSet<>(removeNameservers);
      Set<String> addStatusesThisDomain = new TreeSet<>(addStatuses);
      Set<String> removeStatusesThisDomain = new TreeSet<>(removeStatuses);

      if (!nameservers.isEmpty() || !admins.isEmpty() || !techs.isEmpty() || !statuses.isEmpty()) {
        if (!nameservers.isEmpty()) {
          ImmutableSortedSet<String> existingNameservers = domainBase.loadNameserverHostNames();
          populateAddRemoveLists(
              ImmutableSet.copyOf(nameservers),
              existingNameservers,
              addNameserversThisDomain,
              removeNameserversThisDomain);
          int numNameservers =
              existingNameservers.size()
                  + addNameserversThisDomain.size()
                  - removeNameserversThisDomain.size();
          checkArgument(
              numNameservers <= 13,
              "The resulting nameservers count for domain %s would be more than 13",
              domain);
        }
        if (!admins.isEmpty() || !techs.isEmpty()) {
          ImmutableSet<String> existingAdmins =
              getContactsOfType(domainBase, DesignatedContact.Type.ADMIN);
          ImmutableSet<String> existingTechs =
              getContactsOfType(domainBase, DesignatedContact.Type.TECH);

          if (!admins.isEmpty()) {
            populateAddRemoveLists(
                ImmutableSet.copyOf(admins),
                existingAdmins,
                addAdminsThisDomain,
                removeAdminsThisDomain);
          }
          if (!techs.isEmpty()) {
            populateAddRemoveLists(
                ImmutableSet.copyOf(techs),
                existingTechs,
                addTechsThisDomain,
                removeTechsThisDomain);
          }
        }
        if (!statuses.isEmpty()) {
          Set<String> currentStatusValues = new HashSet<>();
          for (StatusValue statusValue : domainBase.getStatusValues()) {
            currentStatusValues.add(statusValue.getXmlName());
          }
          populateAddRemoveLists(
              ImmutableSet.copyOf(statuses),
              currentStatusValues,
              addStatusesThisDomain,
              removeStatusesThisDomain);
        }
      }

      boolean add =
          (!addNameserversThisDomain.isEmpty()
              || !addAdminsThisDomain.isEmpty()
              || !addTechsThisDomain.isEmpty()
              || !addStatusesThisDomain.isEmpty());

      boolean remove =
          (!removeNameserversThisDomain.isEmpty()
              || !removeAdminsThisDomain.isEmpty()
              || !removeTechsThisDomain.isEmpty()
              || !removeStatusesThisDomain.isEmpty());

      boolean change = (registrant != null || password != null);
      boolean secDns =
          (!addDsRecords.isEmpty()
              || !removeDsRecords.isEmpty()
              || !dsRecords.isEmpty()
              || clearDsRecords);

      if (!add && !remove && !change && !secDns && autorenews == null) {
        logger.atInfo().log("No changes need to be made to domain %s", domain);
        continue;
      }

      // If autorenew is being turned off and this domain is already in the autorenew grace period,
      // then we want to warn the user that they might want to delete it instead.
      if (Boolean.FALSE.equals(autorenews)) {
        if (domainBase.getGracePeriods().stream()
            .map(GracePeriodBase::getType)
            .anyMatch(isEqual(AUTO_RENEW))) {
          autorenewGracePeriodWarningDomains.add(domain);
        }
      }

      setSoyTemplate(DomainUpdateSoyInfo.getInstance(), DomainUpdateSoyInfo.DOMAINUPDATE);
      SoyMapData soyMapData =
          new SoyMapData(
              "domain", domain,
              "add", add,
              "addNameservers", addNameserversThisDomain,
              "addAdmins", addAdminsThisDomain,
              "addTechs", addTechsThisDomain,
              "addStatuses", addStatusesThisDomain,
              "remove", remove,
              "removeNameservers", removeNameserversThisDomain,
              "removeAdmins", removeAdminsThisDomain,
              "removeTechs", removeTechsThisDomain,
              "removeStatuses", removeStatusesThisDomain,
              "change", change,
              "registrant", registrant,
              "password", password,
              "secdns", secDns,
              "addDsRecords", DsRecord.convertToSoy(addDsRecords),
              "removeDsRecords", DsRecord.convertToSoy(removeDsRecords),
              "removeAllDsRecords", clearDsRecords);
      if (autorenews != null) {
        soyMapData.put("autorenews", autorenews.toString());
      }
      addSoyRecord(clientId, soyMapData);
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

  private void populateAddRemoveLists(
      Set<String> targetSet, Set<String> oldSet, Set<String> addSet, Set<String> removeSet) {
    addSet.addAll(Sets.difference(targetSet, oldSet));
    removeSet.addAll(Sets.difference(oldSet, targetSet));
  }

  ImmutableSet<String> getContactsOfType(
      DomainBase domainBase, final DesignatedContact.Type contactType) {
    return domainBase.getContacts().stream()
        .filter(contact -> contact.getType().equals(contactType))
        .map(contact -> tm().loadByKey(contact.getContactKey()).getContactId())
        .collect(toImmutableSet());
  }
}
