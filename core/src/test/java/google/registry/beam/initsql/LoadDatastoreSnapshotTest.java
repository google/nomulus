// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.initsql;

import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newRegistry;

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.io.File;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit test for {@link Transforms#loadDatastoreSnapshot}.
 *
 * <p>The test setup involves three entities, one Registry, one Domain, and two Contacts. Events
 * happen in the following order:
 *
 * <ol>
 *   <li>Registry and a filler Contact are inserted to Datastore.
 *   <li>A CommitLog is persisted.
 *   <li>Registry is updated.
 *   <li>Another Contact and Domain are inserted into Datastore.
 *   <li>Datastore is exported, but misses the newly inserted Contact.
 *   <li>Filler Contact is deleted.
 *   <li>A second CommitLog is persisted.
 *   <li>Domain is updated in the Datastore.
 *   <li>The third and last CommitLog is persisted.
 * </ol>
 *
 * The final snapshot includes Registry, Domain, and Contact. This scenario verifies that:
 *
 * <ul>
 *   <li>Incremental changes committed before an export does not override the exported valie.
 *   <li>Entity missed by an export can be recovered from later CommitLogs.
 *   <li>Multiple changes to an entity is applied in order.
 *   <li>Deletes are properly handled.
 * </ul>
 */
@RunWith(JUnit4.class)
public class LoadDatastoreSnapshotTest {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private static final ImmutableList<Class<?>> ALL_KINDS =
      ImmutableList.of(Registry.class, ContactResource.class, DomainBase.class);
  private static final ImmutableSet<String> ALL_KIND_STRS =
      ALL_KINDS.stream().map(Key::getKind).collect(ImmutableSet.toImmutableSet());

  @Rule public final transient TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule public final transient InjectRule injectRule = new InjectRule();

  @Rule
  public final transient TestPipeline pipeline =
      TestPipeline.create().enableAbandonedNodeEnforcement(true);

  private FakeClock fakeClock;
  private File exportRootDir;
  private File exportDir;
  private File commitLogsDir;

  // Canned data:
  private transient Entity dsRegistry;
  private transient Entity dsContact;
  private transient Entity dsDomain;

  private transient DateTime registryLastUpdateTime;
  private transient DateTime contactLastUpdateTime;
  private transient DateTime domainLastUpdateTime;

  @Before
  public void beforeEach() throws Exception {
    fakeClock = new FakeClock(START_TIME);
    try (BackupTestStore store = new BackupTestStore(fakeClock)) {
      injectRule.setStaticField(Ofy.class, "clock", fakeClock);

      exportRootDir = temporaryFolder.newFolder();
      commitLogsDir = temporaryFolder.newFolder();

      Registry registry = newRegistry("tld1", "TLD1");
      ContactResource fillerContact = newContactResource("contact_filler");
      store.insertOrUpdate(registry, fillerContact);
      store.saveCommitLogs(commitLogsDir.getAbsolutePath());

      registry =
          registry
              .asBuilder()
              .setCreateBillingCost(registry.getStandardCreateCost().plus(1.0d))
              .build();
      registryLastUpdateTime = fakeClock.nowUtc();
      store.insertOrUpdate(registry);

      ContactResource contact = newContactResource("contact");
      DomainBase domain = newDomainBase("domain1.tld1", contact);
      contactLastUpdateTime = fakeClock.nowUtc();
      store.insertOrUpdate(contact, domain);
      exportDir =
          store.export(
              exportRootDir.getAbsolutePath(), ALL_KINDS, ImmutableSet.of(Key.create(contact)));

      store.delete(fillerContact);
      store.saveCommitLogs(commitLogsDir.getAbsolutePath());

      domain =
          domain
              .asBuilder()
              .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("NewPass")))
              .build();
      domainLastUpdateTime = fakeClock.nowUtc();
      store.insertOrUpdate(domain);
      store.saveCommitLogs(commitLogsDir.getAbsolutePath());

      fakeClock.advanceOneMilli();

      // Save persisted data for assertions.
      dsRegistry = store.loadAsDatastoreEntity(registry);
      dsContact = store.loadAsDatastoreEntity(contact);
      dsDomain = store.loadAsDatastoreEntity(domain);
    }
  }

  @Test
  public void loadDatastoreSnapshot() {
    PCollectionTuple snapshot =
        pipeline.apply(
            Transforms.loadDatastoreSnapshot(
                exportDir.getAbsolutePath(),
                commitLogsDir.getAbsolutePath(),
                START_TIME,
                fakeClock.nowUtc(),
                ALL_KIND_STRS));
    InitSqlTestUtils.assertContainsExactlyElementsIn(
        snapshot.get(Transforms.createTagForKind("DomainBase")),
        KV.of(domainLastUpdateTime.getMillis(), dsDomain));
    InitSqlTestUtils.assertContainsExactlyElementsIn(
        snapshot.get(Transforms.createTagForKind("Registry")),
        KV.of(registryLastUpdateTime.getMillis(), dsRegistry));
    InitSqlTestUtils.assertContainsExactlyElementsIn(
        snapshot.get(Transforms.createTagForKind("ContactResource")),
        KV.of(contactLastUpdateTime.getMillis(), dsContact));
    pipeline.run();
  }
}
