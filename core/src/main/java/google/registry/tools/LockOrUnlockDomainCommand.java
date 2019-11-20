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
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.Result.Code;
import google.registry.model.registry.RegistryLockDao;
import google.registry.schema.domain.RegistryLock;
import google.registry.util.Retrier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Shared base class for commands to registry lock or unlock a domain via EPP. */
public abstract class LockOrUnlockDomainCommand extends MutatingEppToolCommand
    implements CommandWithCloudSql {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected static final ImmutableSet<StatusValue> REGISTRY_LOCK_STATUSES =
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

  @Inject
  Retrier retrier;

  private final List<RegistryLock> locksToSave = new ArrayList<>();

  protected ImmutableSet<String> getDomains() {
    return ImmutableSet.copyOf(mainParameters);
  }

  @Override
  protected void initEppToolCommand() throws Exception {
    // Superuser status is required to update registry lock statuses.
    superuser = true;

    // Default clientId to the registry registrar account if otherwise unspecified.
    if (clientId == null) {
      clientId = registryAdminClientId;
    }
    String duplicates = Joiner.on(", ").join(findDuplicates(mainParameters));
    checkArgument(duplicates.isEmpty(), "Duplicate domain arguments found: '%s'", duplicates);
    initMutatingEppToolCommand();
    System.out.println(
        "== ENSURE THAT YOU HAVE AUTHENTICATED THE REGISTRAR BEFORE RUNNING THIS COMMAND ==");
  }

  protected void stageRegistryLockObject(
      DomainBase domain, DateTime now, RegistryLock.Action lockAction) {
    locksToSave.add(
        new RegistryLock.Builder()
            .setRepoId(domain.getRepoId())
            .setDomainName(domain.getFullyQualifiedDomainName())
            .setCreationTimestamp(CreateAutoTimestamp.create(now))
            .setCompletionTimestamp(now)
            .setAction(lockAction)
            .setRegistrarPocId("admin")
            .setRegistrarId(clientId)
            .isSuperuser(true)
            .setVerificationCode(UUID.randomUUID().toString())
            .build());
  }

  @Override
  public String execute() throws Exception {
    String result = super.execute();
    if (result.contains(Code.SUCCESS.msg)) {
      try {
        locksToSave.forEach(
            lock ->
                retrier.callWithRetry(() -> RegistryLockDao.save(lock), RuntimeException.class));
      } catch (Throwable t) {
        String message =
            String.format("Error when saving registry lock object: %s", t.getMessage());
        logger.atSevere().withCause(t).log(message);
        result += message + "\n";
      }
    }
    return result;
  }
}
