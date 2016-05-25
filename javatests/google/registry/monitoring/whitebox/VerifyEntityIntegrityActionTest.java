// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.monitoring.whitebox;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.deleteResource;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistDeletedContact;
import static google.registry.testing.DatastoreHelper.persistDomainAsDeleted;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.api.client.util.Data;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.TableDataInsertAllRequest;
import com.google.api.services.bigquery.model.TableDataInsertAllRequest.Rows;
import com.google.api.services.bigquery.model.TableDataInsertAllResponse;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Ref;

import google.registry.bigquery.BigqueryFactory;
import google.registry.config.RegistryEnvironment;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainResource;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyContactIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyDomainIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyHostIndex;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectRule;
import google.registry.testing.mapreduce.MapreduceTestCase;
import google.registry.util.Retrier;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Map;

/** Unit tests for {@link VerifyEntityIntegrityAction}. */
@RunWith(MockitoJUnitRunner.class)
public class VerifyEntityIntegrityActionTest
    extends MapreduceTestCase<VerifyEntityIntegrityAction> {

  @Rule
  public final InjectRule inject = new InjectRule();

  private VerifyEntityIntegrityStreamer integrity;
  private ArgumentCaptor<TableDataInsertAllRequest> rowsCaptor;
  private final DateTime now = DateTime.parse("2012-01-02T03:04:05Z");

  @Mock
  private Bigquery bigquery;

  @Mock
  private Bigquery.Tabledata bigqueryTableData;

  @Mock
  private Bigquery.Tabledata.InsertAll bigqueryInsertAll;

  @Mock
  private BigqueryFactory bigqueryFactory;

  @Mock
  private VerifyEntityIntegrityStreamerFactory streamerFactory;

  @Before
  public void before() throws Exception {
    createTld("tld");

    action = new VerifyEntityIntegrityAction();
    action.mrRunner = new MapreduceRunner(Optional.of(2), Optional.of(2));
    action.response = new FakeResponse();
    WhiteboxComponent component = mock(WhiteboxComponent.class);
    inject.setStaticField(VerifyEntityIntegrityAction.class, "component", component);
    integrity =
        new VerifyEntityIntegrityStreamer(
            bigqueryFactory,
            RegistryEnvironment.UNITTEST,
            new Retrier(new FakeSleeper(new FakeClock()), 1),
            Suppliers.ofInstance("rowid"),
            now);
    when(bigqueryFactory.create(anyString(), anyString(), anyString())).thenReturn(bigquery);
    when(component.verifyEntityIntegrityStreamerFactory()).thenReturn(streamerFactory);
    when(streamerFactory.create(any(DateTime.class))).thenReturn(integrity);
    when(bigquery.tabledata()).thenReturn(bigqueryTableData);
    rowsCaptor = ArgumentCaptor.forClass(TableDataInsertAllRequest.class);
    when(bigqueryTableData.insertAll(anyString(), anyString(), anyString(), rowsCaptor.capture()))
            .thenReturn(bigqueryInsertAll);
    when(bigqueryInsertAll.execute()).thenReturn(new TableDataInsertAllResponse());

  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  @Test
  public void test_singleDomain_noBadInvariants() throws Exception {
    persistActiveDomain("ninetails.tld");
    runMapreduce();
    verifyZeroInteractions(bigquery);
  }

  @Test
  public void test_lotsOfData_noBadInvariants() throws Exception {
    createTld("march");
    ContactResource contact = persistActiveContact("longbottom");
    persistResource(newDomainResource("ninetails.tld", contact));
    persistResource(newDomainResource("tentails.tld", contact));
    persistDomainAsDeleted(newDomainResource("long.march", contact), now.minusMonths(4));
    persistResource(
        newDomainResource("long.march", contact)
            .asBuilder()
            .setCreationTime(now.minusMonths(3))
            .build());
    persistDeletedContact("ricketycricket", now.minusDays(3));
    persistDeletedContact("ricketycricket", now.minusDays(2));
    persistDeletedContact("ricketycricket", now.minusDays(1));
    persistActiveContact("ricketycricket");
    persistActiveHost("ns9001.example.net");
    runMapreduce();
    verifyZeroInteractions(bigquery);
  }

  @Test
  public void test_missingFki() throws Exception {
    persistActiveDomain("ninetails.tld");
    ForeignKeyIndex<DomainResource> fki =
        ForeignKeyIndex.load(DomainResource.class, "ninetails.tld", DateTime.now(DateTimeZone.UTC));
    deleteResource(fki);
    runMapreduce();
    assertIntegrityErrors(IntegrityError.create(
        "ninetails.tld", "DomainBase", "Missing foreign key index for EppResource"));
  }

  @Test
  public void test_missingEppResourceIndex() throws Exception {
    Key<ContactResource> cooperKey = Key.create(persistActiveContact("cooper"));
    deleteResource(EppResourceIndex.create(cooperKey));
    runMapreduce();
    assertIntegrityErrors(IntegrityError.create(
        Data.NULL_STRING, cooperKey, "Missing EPP resource index for EPP resource"));
  }

  @Test
  public void test_referencesToHostsThatDontExist() throws Exception {
    Key<HostResource> missingHost1 = Key.create(HostResource.class, "DEADBEEF-ROID");
    Key<HostResource> missingHost2 = Key.create(HostResource.class, "123ABC-ROID");
    Key<HostResource> missingHost3 = Key.create(HostResource.class, "FADDACA-ROID");
    DomainResource domain =
        persistResource(
            newDomainResource("blah.tld")
                .asBuilder()
                .setNameservers(
                    ImmutableSet.of(
                        Ref.create(missingHost1),
                        Ref.create(missingHost2),
                        Ref.create(missingHost3)))
                .build());
    Key<DomainResource> domainKey = Key.create(domain);
    runMapreduce();
    assertIntegrityErrors(
        IntegrityError.create(domainKey, missingHost1, "Target entity does not exist"),
        IntegrityError.create(domainKey, missingHost2, "Target entity does not exist"),
        IntegrityError.create(domainKey, missingHost3, "Target entity does not exist"));
  }

  @Test
  public void test_overlappingActivePeriods() throws Exception {
    ContactResource contact123 = persistActiveContact("contact123");
    // These two have overlapping active periods because they will have both been created at
    // START_OF_TIME.
    DomainResource domain1 =
        persistDomainAsDeleted(newDomainResource("penny.tld", contact123), now.minusYears(2));
    DomainResource domain2 = persistActiveDomain("penny.tld");
    runMapreduce();
    assertIntegrityErrors(
        IntegrityError.create(
            ForeignKeyDomainIndex.createKey(domain2),
            Key.create(domain1),
            "Found inactive resource deleted more recently than when active resource was created"));
  }

  @Test
  public void test_multipleActiveContactsWithSameContactId() throws Exception {
    ContactResource contact1 = persistActiveContact("dupeid");
    ContactResource contact2 = persistActiveContact("dupeid");
    runMapreduce();
    assertIntegrityErrors(
        IntegrityError.create(
            Key.create(ForeignKeyContactIndex.class, "dupeid"),
            Key.create(contact1),
            "Multiple active EppResources with same foreign key"),
        IntegrityError.create(
            Key.create(ForeignKeyContactIndex.class, "dupeid"),
            Key.create(contact2),
            "Multiple active EppResources with same foreign key"));
  }

  @Test
  public void test_deletedFkiWithOldHostnameIsntAnError() throws Exception {
    HostResource hostLosing = persistActiveHost("losing.example.tld");
    persistResource(
        hostLosing.asBuilder().setFullyQualifiedHostName("gaining.example.tld").build());
    persistSimpleResource(
        ForeignKeyHostIndex.create(hostLosing, DateTime.parse("2010-01-01T00:00:00Z")));
    // The old FKI with a primary key of "losing.example.tld" still exists, and it points to the
    // resource which has a hostname of "gaining.example.tld", but this isn't an error because the
    // FKI is soft-deleted.
    runMapreduce();
    verifyZeroInteractions(bigquery);
  }

  @Test
  public void test_fkiWithDifferentHostnameDeletedMoreRecentlyIsAnError() throws Exception {
    HostResource hostLosing = persistActiveHost("losing.example.tld");
    HostResource hostGaining = persistResource(
        hostLosing
            .asBuilder()
            .setFullyQualifiedHostName("gaining.example.tld")
            .setDeletionTime(DateTime.parse("2013-01-01T00:00:00Z"))
            .build());
    ForeignKeyIndex<?> fki = persistSimpleResource(
        ForeignKeyHostIndex.create(hostLosing, DateTime.parse("2014-01-01T00:00:00Z")));
    // The old FKI is pointing to the old name but has a deletion time more recent than the deleted
    // host, so it should have the same foreign key.
    runMapreduce();
    assertIntegrityErrors(
        IntegrityError.create(
            Key.create(fki),
            Key.create(hostGaining),
            "Foreign key index points to EppResource with different foreign key"));
  }

  /** Encapsulates the data representing a single integrity error. */
  private static class IntegrityError {
    String source;
    String target;
    String message;

    static IntegrityError create(Object source, Object target, String message) {
      IntegrityError instance = new IntegrityError();
      instance.source = source.toString();
      instance.target = target.toString();
      instance.message = message;
      return instance;
    }

    /**
     * Returns a Map representing the JSON blob corresponding to the BigQuery output for this
     * integrity violation at the given scan time.
     */
    Map<String, Object> toMap(DateTime scanTime) {
      return new ImmutableMap.Builder<String, Object>()
          .put("scanTime", new com.google.api.client.util.DateTime(scanTime.toDate()))
          .put("source", source)
          .put("target", target)
          .put("message", message)
          .build();
    }

    private IntegrityError() {}
  }

  /** Asserts that the given integrity errors, and no others, were logged to BigQuery. */
  private void assertIntegrityErrors(IntegrityError... errors) {
    ImmutableList.Builder<Rows> expected = new ImmutableList.Builder<>();
    for (IntegrityError error : errors) {
      expected.add(new Rows().setInsertId("rowid").setJson(error.toMap(now)));
    }
    ImmutableList.Builder<Rows> allRows = new ImmutableList.Builder<>();
    for (TableDataInsertAllRequest req : rowsCaptor.getAllValues()) {
      allRows.addAll(req.getRows());
    }
    assertThat(allRows.build()).containsExactlyElementsIn(expected.build());
  }
}
