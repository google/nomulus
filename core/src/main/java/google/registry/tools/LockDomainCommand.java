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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.template.soy.data.SoyMapData;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.schema.domain.RegistryLock.Action;
import google.registry.tools.soy.DomainUpdateSoyInfo;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * A command to registry lock domain names via EPP.
 *
 * <p>A registry lock consists of server-side statuses preventing deletes, updates, and transfers.
 */
@Parameters(separators = " =", commandDescription = "Registry lock a domain via EPP.")
public class LockDomainCommand extends LockOrUnlockDomainCommand {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Override
  protected void initMutatingEppToolCommand() {
    // Project all domains as of the same time so that argument order doesn't affect behavior.
    DateTime now = DateTime.now(UTC);
    for (String domain : getDomains()) {
      Optional<DomainBase> domainBase = loadByForeignKey(DomainBase.class, domain, now);
      checkArgumentPresent(domainBase, "Domain '%s' does not exist or is deleted", domain);
      ImmutableSet<StatusValue> statusesToAdd =
          Sets.difference(REGISTRY_LOCK_STATUSES, domainBase.get().getStatusValues())
              .immutableCopy();
      if (statusesToAdd.isEmpty()) {
        logger.atInfo().log("Domain '%s' is already locked and needs no updates.", domain);
        continue;
      }

      setSoyTemplate(DomainUpdateSoyInfo.getInstance(), DomainUpdateSoyInfo.DOMAINUPDATE);
      addSoyRecord(
          clientId,
          new SoyMapData(
              "domain", domain,
              "add", true,
              "addNameservers", ImmutableList.of(),
              "addAdmins", ImmutableList.of(),
              "addTechs", ImmutableList.of(),
              "addStatuses",
                  statusesToAdd.stream().map(StatusValue::getXmlName).collect(toImmutableList()),
              "remove", false,
              "removeNameservers", ImmutableList.of(),
              "removeAdmins", ImmutableList.of(),
              "removeTechs", ImmutableList.of(),
              "removeStatuses", ImmutableList.of(),
              "change", false,
              "secdns", false,
              "addDsRecords", ImmutableList.of(),
              "removeDsRecords", ImmutableList.of(),
              "removeAllDsRecords", false));
      saveLockObject(domainBase.get(), now, Action.LOCK);
    }
  }
}
