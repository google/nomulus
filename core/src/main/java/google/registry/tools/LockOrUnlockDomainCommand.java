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
import static google.registry.model.eppcommon.StatusValue.SERVER_DELETE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_TRANSFER_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.util.CollectionUtils.findDuplicates;

import com.beust.jcommander.Parameter;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.eppcommon.StatusValue;
import google.registry.schema.domain.RegistryLock;
import google.registry.util.Clock;
import java.util.List;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Shared base class for commands to registry lock or unlock a domain via EPP. */
public abstract class LockOrUnlockDomainCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final ImmutableSet<StatusValue> REGISTRY_LOCK_STATUSES =
      ImmutableSet.of(
          SERVER_DELETE_PROHIBITED, SERVER_TRANSFER_PROHIBITED, SERVER_UPDATE_PROHIBITED);

  @Parameter(
      names = {"-c", "--client"},
      description =
          "Client ID of the requesting registrar if applicable, otherwise the registry registrar")
  String clientId;

  @Parameter(description = "Names of the domains", required = true)
  private List<String> mainParameters;

  @Inject
  @Config("registryAdminClientId")
  String registryAdminClientId;

  @Inject Clock clock;

  @Inject DomainLockUtils domainLockUtils;

  protected DateTime now;
  protected ImmutableSet<String> relevantDomains = ImmutableSet.of();

  protected ImmutableSet<String> getDomains() {
    return ImmutableSet.copyOf(mainParameters);
  }

  @Override
  protected void init() {
    // use the same time for everything
    now = clock.nowUtc();
    // Default clientId to the registry registrar account if otherwise unspecified.
    if (clientId == null) {
      clientId = registryAdminClientId;
    }
    String duplicates = Joiner.on(", ").join(findDuplicates(mainParameters));
    checkArgument(duplicates.isEmpty(), "Duplicate domain arguments found: '%s'", duplicates);
    System.out.println(
        "== ENSURE THAT YOU HAVE AUTHENTICATED THE REGISTRAR BEFORE RUNNING THIS COMMAND ==");
    relevantDomains = getRelevantDomains();
  }

  @Override
  protected String execute() {
    int failures = 0;
    for (String domain : relevantDomains) {
      try {
        RegistryLock lock = createLock(domain);
        finalizeLockOrUnlockRequest(lock);
      } catch (Throwable t) {
        Throwable rootCause = Throwables.getRootCause(t);
        logger.atSevere().withCause(rootCause).log("Error when (un)locking domain %s.", domain);
        failures++;
      }
    }
    if (failures == 0) {
      return String.format("Successfully locked/unlocked %d domains.", relevantDomains.size());
    } else {
      return String.format(
          "Successfully locked/unlocked %d domains with %d failures.",
          relevantDomains.size() - failures, failures);
    }
  }

  protected abstract ImmutableSet<String> getRelevantDomains();

  protected abstract RegistryLock createLock(String domain);

  protected abstract void finalizeLockOrUnlockRequest(RegistryLock lock);
}
