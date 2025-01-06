// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows;

import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.LogsSubject.assertAboutLogs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.testing.TestLogHandler;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.host.HostBase;
import google.registry.model.host.HostHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tmch.ClaimsList;
import google.registry.model.tmch.ClaimsListDao;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.TestCacheExtension;
import google.registry.util.JdkLoggerConfig;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.time.Duration;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.json.simple.JSONValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Base class for resource flow unit tests.
 *
 * @param <F> the flow type
 * @param <R> the resource type
 */
public abstract class ResourceFlowTestCase<F extends Flow, R extends EppResource>
    extends FlowTestCase<F> {

  private final TestLogHandler logHandler = new TestLogHandler();

  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withClaimsListCache(Duration.ofHours(6)).build();

  @BeforeEach
  void beforeResourceFlowTestCase() {
    // Attach TestLogHandler to the root logger, so it has access to all log messages.
    // Note that in theory for assertIcannReportingActivityFieldLogged() below it would suffice to
    // attach it only to the FlowRunner logger, but for some reason this doesn't work for all flows.
    JdkLoggerConfig.getConfig("").addHandler(logHandler);
  }

  @Nullable
  protected R reloadResourceByForeignKey(DateTime now) throws Exception {
    return loadByForeignKey(getResourceClass(), getUniqueIdFromCommand(), now).orElse(null);
  }

  @Nullable
  protected R reloadResourceByForeignKey() throws Exception {
    return reloadResourceByForeignKey(clock.nowUtc());
  }

  protected <T extends EppResource> T reloadResourceAndCloneAtTime(T resource, DateTime now) {
    @SuppressWarnings("unchecked")
    T refreshedResource =
        (T) tm().transact(() -> tm().loadByEntity(resource)).cloneProjectedAtTime(now);
    return refreshedResource;
  }

  private ResourceCommand.SingleResourceCommand getResourceCommand() throws Exception {
    return (ResourceCommand.SingleResourceCommand)
        ((ResourceCommandWrapper) eppLoader.getEpp().getCommandWrapper().getCommand())
            .getResourceCommand();
  }

  protected String getUniqueIdFromCommand() throws Exception {
    return getResourceCommand().getTargetId();
  }

  private Class<R> getResourceClass() {
    return new TypeInstantiator<R>(getClass()) {}.getExactType();
  }

  /** Persists a testing claims list to Cloud SQL. */
  protected void persistClaimsList(ImmutableMap<String, String> labelsToKeys) {
    ClaimsListDao.save(ClaimsList.create(clock.nowUtc(), labelsToKeys));
  }

  protected void assertClientIdFieldLogged(String registrarId) {
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.INFO, "FLOW-LOG-SIGNATURE-METADATA")
        .which()
        .contains("\"clientId\":" + JSONValue.toJSONString(registrarId));
  }

  protected void assertTldsFieldLogged(String... tlds) {
    assertAboutLogs().that(logHandler)
        .hasLogAtLevelWithMessage(Level.INFO, "FLOW-LOG-SIGNATURE-METADATA")
        .which()
        .contains("\"tlds\":" + JSONValue.toJSONString(ImmutableList.copyOf(tlds)));
  }

  protected void assertIcannReportingActivityFieldLogged(String fieldName) {
    assertAboutLogs().that(logHandler)
        .hasLogAtLevelWithMessage(Level.INFO, "FLOW-LOG-SIGNATURE-METADATA")
        .which()
        .contains("\"icannActivityReportField\":" + JSONValue.toJSONString(fieldName));
  }

  protected void assertLastHistoryContainsResource(EppResource resource) {
    HistoryEntry historyEntry = Iterables.getLast(DatabaseHelper.getHistoryEntries(resource));
    if (resource instanceof ContactBase) {
      ContactHistory contactHistory = (ContactHistory) historyEntry;
      // Don't use direct equals comparison since one might be a subclass of the other
      assertAboutImmutableObjects()
          .that(contactHistory.getContactBase().get())
          .hasFieldsEqualTo(resource);
    } else if (resource instanceof DomainBase) {
      DomainHistory domainHistory = (DomainHistory) historyEntry;
      assertAboutImmutableObjects()
          .that(domainHistory.getDomainBase().get())
          .isEqualExceptFields(resource, "gracePeriods", "dsData", "nsHosts");
    } else if (resource instanceof HostBase) {
      HostHistory hostHistory = (HostHistory) historyEntry;
      // Don't use direct equals comparison since one might be a subclass of the other
      assertAboutImmutableObjects()
          .that(hostHistory.getHostBase().get())
          .hasFieldsEqualTo(resource);
    }
  }
}
