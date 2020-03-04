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
import static com.google.common.collect.Iterables.partition;
import static google.registry.model.eppcommon.StatusValue.SERVER_DELETE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_TRANSFER_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.findDuplicates;

import com.beust.jcommander.Parameter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.eppcommon.StatusValue;
import java.util.List;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Shared base class for commands to registry lock or unlock a domain via EPP. */
public abstract class LockOrUnlockDomainCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int BATCH_SIZE = 10;

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

  @Inject DomainLockUtils domainLockUtils;

  protected ImmutableSet<String> getDomains() {
    return ImmutableSet.copyOf(mainParameters);
  }

  @Override
  protected void init() {
    // Default clientId to the registry registrar account if otherwise unspecified.
    if (clientId == null) {
      clientId = registryAdminClientId;
    }
    String duplicates = Joiner.on(", ").join(findDuplicates(mainParameters));
    checkArgument(duplicates.isEmpty(), "Duplicate domain arguments found: '%s'", duplicates);
    System.out.println(
        "== ENSURE THAT YOU HAVE AUTHENTICATED THE REGISTRAR BEFORE RUNNING THIS COMMAND ==");
  }

  @Override
  protected String execute() {
    ImmutableSet.Builder<String> successfulDomainsBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<String> skippedDomainsBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<String> failedDomainsBuilder = new ImmutableSet.Builder<>();
    partition(getDomains(), BATCH_SIZE)
        .forEach(
            batch ->
                tm().transact(
                        () -> {
                          for (String domain : batch) {
                            if (shouldApplyToDomain(domain, tm().getTransactionTime())) {
                              try {
                                createAndApplyRequest(domain);
                              } catch (Throwable t) {
                                logger.atSevere().withCause(t).log(
                                    "Error when (un)locking domain %s.", domain);
                                failedDomainsBuilder.add(domain);
                              }
                              successfulDomainsBuilder.add(domain);
                            } else {
                              skippedDomainsBuilder.add(domain);
                            }
                          }
                        }));
    ImmutableSet<String> successfulDomains = successfulDomainsBuilder.build();
    ImmutableSet<String> skippedDomains = skippedDomainsBuilder.build();
    ImmutableSet<String> failedDomains = failedDomainsBuilder.build();
    return String.format(
        "Successfully locked/unlocked domains:\n%s\nSkipped domains:\n%s\nFailed domains:\n%s",
        successfulDomains, skippedDomains, failedDomains);
  }

  protected abstract boolean shouldApplyToDomain(String domain, DateTime now);

  protected abstract void createAndApplyRequest(String domain);
}
