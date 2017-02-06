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

package google.registry.rde.imports;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.dns.DnsConstants.DNS_TARGET_NAME_PARAM;
import static google.registry.dns.DnsConstants.DNS_TARGET_TYPE_PARAM;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.request.RequestParameters.PARAM_TLD;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getHistoryEntries;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;

import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.dns.DnsConstants;
import google.registry.dns.DnsQueue;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Seconds;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link RdeDomainImportAction}. */
@RunWith(MockitoJUnitRunner.class)
public class RdeDomainImportActionTest extends MapreduceTestCase<RdeDomainImportAction> {

  private static final ByteSource DEPOSIT_1_DOMAIN = RdeImportsTestData.get("deposit_1_domain.xml");
  private static final String IMPORT_BUCKET_NAME = "import-bucket";
  private static final String IMPORT_FILE_NAME = "escrow-file.xml";

  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  private MapreduceRunner mrRunner;

  private Response response;

  @Before
  public void before() throws Exception {
    createTld("test");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    response = new FakeResponse();
    mrRunner = makeDefaultRunner();
    action = new RdeDomainImportAction(
        mrRunner,
        response,
        IMPORT_BUCKET_NAME,
        IMPORT_FILE_NAME,
        Optional.<Integer>of(3));
  }

  @Test
  public void test_mapreduceSuccessfullyImportsDomain() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    runMapreduce();
    List<DomainResource> domains = ofy().load().type(DomainResource.class).list();
    assertThat(domains).hasSize(1);
    checkDomain(domains.get(0));
  }

  @Test
  public void test_mapreduceSuccessfullyCreatesHistoryEntry() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    runMapreduce();
    List<DomainResource> domains = ofy().load().type(DomainResource.class).list();
    DomainResource domain = domains.get(0);
    // verify history entry
    List<HistoryEntry> historyEntries = getHistoryEntries(domain);
    assertThat(historyEntries).hasSize(1);
    checkHistoryEntry(historyEntries.get(0), domain);
  }

  /** Ensures that a second pass on a domain does not import a new domain. */
  @Test
  public void test_mapreduceTwiceDoesNotDuplicateResources() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    // Create domain and history entry first
    DomainResource existingDomain =
        persistResource(
            newDomainResource("example1.test").asBuilder().setRepoId("Dexample1-TEST").build());
    persistSimpleResource(createHistoryEntry(
        existingDomain.getRepoId(),
        existingDomain.getCurrentSponsorClientId(),
        loadDomainXml(DEPOSIT_1_DOMAIN)));
    // Simulate running a second import and verify that the resources
    // aren't imported twice (only one domain, and one history entry)
    pushToGcs(DEPOSIT_1_DOMAIN);
    runMapreduce();
    List<DomainResource> domains = ofy().load().type(DomainResource.class).list();
    assertThat(domains.size()).isEqualTo(1);
    DomainResource domain = domains.get(0);
    // verify history entry
    List<HistoryEntry> historyEntries = getHistoryEntries(domain);
    assertThat(historyEntries).hasSize(1);
    checkHistoryEntry(historyEntries.get(0), domain);
  }

  /** Ensures that dns publishing is kicked off on domain import */
  @Test
  public void test_mapreducePublishesToDns() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    runMapreduce();
    DnsQueue dnsQueue = DnsQueue.create();
    List<TaskHandle> tasks = dnsQueue.leaseTasks(Duration.standardSeconds(1));
    assertThat(tasks.size()).isEqualTo(1);
    TaskHandle dnsTask = tasks.get(0);
    Map<String, String> params = ImmutableMap.copyOf(dnsTask.extractParams());
    assertThat(params.get(RequestParameters.PARAM_TLD)).isEqualTo("test");
    assertThat(params.get(DnsConstants.DNS_TARGET_TYPE_PARAM)).isEqualTo("DOMAIN");
    assertThat(params.get(DnsConstants.DNS_TARGET_NAME_PARAM)).isEqualTo("example1.test");

    /*
             .method(Method.PULL)
        .param(DNS_TARGET_TYPE_PARAM, targetType.toString())
        .param(DNS_TARGET_NAME_PARAM, targetName)
        .param(PARAM_TLD, tld));
     */
  }

  /** Verify history entry fields are correct */
  private void checkHistoryEntry(HistoryEntry entry, DomainResource parent) {
    assertThat(entry.getType()).isEqualTo(HistoryEntry.Type.RDE_IMPORT);
    assertThat(entry.getClientId()).isEqualTo(parent.getCurrentSponsorClientId());
    assertThat(entry.getXmlBytes().length).isGreaterThan(0);
    assertThat(entry.getBySuperuser()).isTrue();
    assertThat(entry.getReason()).isEqualTo("RDE Import");
    assertThat(entry.getRequestedByRegistrar()).isEqualTo(false);
    assertThat(entry.getParent()).isEqualTo(Key.create(parent));
  }

  /** Verifies that domain id and ROID match expected values */
  private void checkDomain(DomainResource domain) {
    assertThat(domain.getFullyQualifiedDomainName()).isEqualTo("example1.test");
    assertThat(domain.getRepoId()).isEqualTo("Dexample1-TEST");
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  private void pushToGcs(ByteSource source) throws IOException {
    try (OutputStream outStream =
            new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize())
                .openOutputStream(new GcsFilename(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME));
        InputStream inStream = source.openStream()) {
      ByteStreams.copy(inStream, outStream);
    }
  }

  private static byte[] loadDomainXml(ByteSource source) throws IOException {
    byte[] result = new byte[((int) source.size())];
    try (InputStream inStream = source.openStream()) {
      ByteStreams.readFully(inStream, result);
    }
    return result;
  }

  private static HistoryEntry createHistoryEntry(String roid, String clid, byte[] objectXml) {
    return new HistoryEntry.Builder()
        .setType(HistoryEntry.Type.RDE_IMPORT)
        .setClientId(clid)
        .setTrid(Trid.create(null))
        .setModificationTime(DateTime.now())
        .setXmlBytes(objectXml)
        .setBySuperuser(true)
        .setReason("RDE Import")
        .setRequestedByRegistrar(false)
        .setParent(Key.create(null, DomainResource.class, roid))
        .build();
  }
}
