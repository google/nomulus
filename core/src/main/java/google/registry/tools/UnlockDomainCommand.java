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

import static google.registry.model.EppResourceUtils.loadByForeignKey;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import org.joda.time.DateTime;

/**
 * A command to registry unlock domain names.
 *
 * <p>A registry lock consists of server-side statuses preventing deletes, updates, and transfers.
 */
@Parameters(separators = " =", commandDescription = "Registry unlock a domain via EPP.")
public class UnlockDomainCommand extends LockOrUnlockDomainCommand {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Override
  protected boolean shouldApplyToDomain(String domain, DateTime now) {
    DomainBase domainBase =
        loadByForeignKey(DomainBase.class, domain, now)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Domain '%s' does not exist or is deleted", domain)));
    ImmutableSet<StatusValue> statusesToRemove =
        Sets.intersection(domainBase.getStatusValues(), REGISTRY_LOCK_STATUSES).immutableCopy();
    if (statusesToRemove.isEmpty()) {
      logger.atInfo().log("Domain '%s' is already unlocked and needs no updates.", domain);
      return false;
    }
    return true;
  }

  @Override
  protected void createAndApplyRequest(String domain) {
    domainLockUtils.administrativelyApplyUnlock(domain, clientId, true);
  }
}
