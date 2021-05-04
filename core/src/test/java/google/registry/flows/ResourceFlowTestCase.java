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

package google.registry.flows;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.flogger.LoggerConfig;
import com.google.common.testing.TestLogHandler;
import com.googlecode.objectify.Key;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.DomainContent;
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.host.HostBase;
import google.registry.model.host.HostHistory;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tmch.ClaimsListDualDatabaseDao;
import google.registry.model.tmch.ClaimsListShard;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.json.simple.JSONValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Base class for resource flow unit tests.
 *
 * @param <F> the flow type
 * @param <R> the resource type
 */
public abstract class ResourceFlowTestCase<F extends Flow, R extends EppResource>
    extends FlowTestCase<F> {

  private final TestLogHandler logHandler = new TestLogHandler();

  @BeforeEach
  void beforeResourceFlowTestCase() {
    // Attach TestLogHandler to the root logger so it has access to all log messages.
    // Note that in theory for assertIcannReportingActivityFieldLogged() below it would suffice to
    // attach it only to the FlowRunner logger, but for some reason this doesn't work for all flows.
    LoggerConfig.getConfig("").addHandler(logHandler);
  }

  @Nullable
  protected R reloadResourceByForeignKey(DateTime now) throws Exception {
    // Force the session to be cleared so that when we read it back, we read from Datastore and not
    // from the transaction's session cache.
    tm().clearSessionCache();
    return loadByForeignKey(getResourceClass(), getUniqueIdFromCommand(), now).orElse(null);
  }

  @Nullable
  protected R reloadResourceByForeignKey() throws Exception {
    return reloadResourceByForeignKey(clock.nowUtc());
  }

  protected <T extends EppResource> T reloadResourceAndCloneAtTime(T resource, DateTime now) {
    // Force the session to be cleared.
    tm().clearSessionCache();
    @SuppressWarnings("unchecked")
    T refreshedResource =
        (T) transactIfJpaTm(() -> tm().loadByEntity(resource)).cloneProjectedAtTime(now);
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

  /** Persists a testing claims list to Datastore that contains a single shard. */
  protected void persistClaimsList(ImmutableMap<String, String> labelsToKeys) {
    ClaimsListDualDatabaseDao.save(ClaimsListShard.create(clock.nowUtc(), labelsToKeys));
  }

  @Test
  void testRequiresLogin() {
    sessionMetadata.setClientId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /**
   * Confirms that an EppResourceIndex entity exists in Datastore for a given resource.
   */
  protected static <T extends EppResource> void assertEppResourceIndexEntityFor(final T resource) {
    if (!tm().isOfy()) {
      // Indices aren't explicitly stored as objects in SQL
      return;
    }
    ImmutableList<EppResourceIndex> indices =
        Streams.stream(
                ofy()
                    .load()
                    .type(EppResourceIndex.class)
                    .filter("kind", Key.getKind(resource.getClass())))
            .filter(
                index ->
                    Key.create(resource).equals(index.getKey())
                        && ofy().load().key(index.getKey()).now().equals(resource))
            .collect(toImmutableList());
    assertThat(indices).hasSize(1);
    assertThat(indices.get(0).getBucket())
        .isEqualTo(EppResourceIndexBucket.getBucketKey(Key.create(resource)));
  }

  /** Asserts the presence of a single enqueued async contact or host deletion */
  protected <T extends EppResource> void assertAsyncDeletionTaskEnqueued(
      T resource, String requestingClientId, Trid trid, boolean isSuperuser) {
    TaskMatcher expected = new TaskMatcher()
        .etaDelta(Duration.standardSeconds(75), Duration.standardSeconds(105)) // expected: 90
        .param("resourceKey", Key.create(resource).getString())
        .param("requestingClientId", requestingClientId)
        .param("serverTransactionId", trid.getServerTransactionId())
        .param("isSuperuser", Boolean.toString(isSuperuser))
        .param("requestedTime", clock.nowUtc().toString());
    trid.getClientTransactionId()
        .ifPresent(clTrid -> expected.param("clientTransactionId", clTrid));
    assertTasksEnqueued("async-delete-pull", expected);
  }


  protected void assertClientIdFieldLogged(String clientId) {
    assertAboutLogs().that(logHandler)
        .hasLogAtLevelWithMessage(Level.INFO, "FLOW-LOG-SIGNATURE-METADATA")
        .which()
        .contains("\"clientId\":" + JSONValue.toJSONString(clientId));
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
    if (!tm().isOfy()) {
      HistoryEntry historyEntry = Iterables.getLast(DatabaseHelper.getHistoryEntries(resource));
      if (resource instanceof ContactBase) {
        ContactHistory contactHistory = (ContactHistory) historyEntry;
        assertThat(contactHistory.getContactBase().get()).isEqualTo(resource);
      } else if (resource instanceof DomainContent) {
        DomainHistory domainHistory = (DomainHistory) historyEntry;
        assertAboutImmutableObjects()
            .that(domainHistory.getDomainContent().get())
            .isEqualExceptFields(resource, "gracePeriods", "dsData", "nsHosts");
      } else if (resource instanceof HostBase) {
        HostHistory hostHistory = (HostHistory) historyEntry;
        assertThat(hostHistory.getHostBase().get()).isEqualTo(resource);
      }
    }
  }
}
