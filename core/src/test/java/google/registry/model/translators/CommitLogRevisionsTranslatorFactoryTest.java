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

package google.registry.model.translators;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardHours;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.common.CrossTldSingleton;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.Ofy;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CommitLogRevisionsTranslatorFactory}. */
@RunWith(JUnit4.class)
public class CommitLogRevisionsTranslatorFactoryTest {

  private static final DateTime START_TIME = DateTime.parse("2000-01-01TZ");

  @Entity
  public static class TestObject extends CrossTldSingleton {
    ImmutableSortedMap<DateTime, Key<CommitLogManifest>> revisions = ImmutableSortedMap.of();
  }

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Rule
  public final InjectRule inject = new InjectRule();

  private final FakeClock clock = new FakeClock(START_TIME);

  @Before
  public void before() {
    ObjectifyService.register(TestObject.class);
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  private void save(final TestObject object) {
    tm().transact(() -> ofy().save().entity(object));
  }

  private TestObject reload() {
    ofy().clearSessionCache();
    return ofy().load().entity(new TestObject()).now();
  }

  @Test
  public void testSave_doesNotMutateOriginalResource() {
     TestObject object = new TestObject();
     save(object);
     assertThat(object.revisions).isEmpty();
     assertThat(reload().revisions).isNotEmpty();
   }

  @Test
  public void testSave_translatorAddsKeyToCommitLogToField() {
    save(new TestObject());
    TestObject object = reload();
    assertThat(object.revisions).hasSize(1);
    assertThat(object.revisions).containsKey(START_TIME);
    CommitLogManifest commitLogManifest = ofy().load().key(object.revisions.get(START_TIME)).now();
    assertThat(commitLogManifest.getCommitTime()).isEqualTo(START_TIME);
  }

  @Test
  public void testSave_twoVersionsOnOneDay_keyToLastCommitLogsGetsStored() {
    save(new TestObject());
    clock.advanceBy(standardHours(1));
    save(reload());
    TestObject object = reload();
    assertThat(object.revisions).hasSize(1);
    assertThat(object.revisions).containsKey(START_TIME.plusHours(1));
  }

  @Test
  public void testSave_twoVersionsOnTwoDays_keyToBothCommitLogsGetsStored() {
    save(new TestObject());
    clock.advanceBy(standardDays(1));
    save(reload());
    TestObject object = reload();
    assertThat(object.revisions).hasSize(2);
    assertThat(object.revisions).containsKey(START_TIME);
    assertThat(object.revisions).containsKey(START_TIME.plusDays(1));
  }

  @Test
  public void testSave_moreThanThirtyDays_truncatedAtThirtyPlusOne() {
    save(new TestObject());
    for (int i = 0; i < 35; i++) {
      clock.advanceBy(standardDays(1));
      save(reload());
    }
    TestObject object = reload();
    assertThat(object.revisions).hasSize(31);
    assertThat(object.revisions.firstKey()).isEqualTo(clock.nowUtc().minusDays(30));
  }

  @Test
  public void testSave_moreThanThirtySparse_keepsOneEntryPrecedingThirtyDays() {
    save(new TestObject());
    assertThat(reload().revisions).hasSize(1);
    assertThat(reload().revisions.firstKey()).isEqualTo(clock.nowUtc().minusDays(0));
    clock.advanceBy(standardDays(29));
    save(reload());
    assertThat(reload().revisions).hasSize(2);
    assertThat(reload().revisions.firstKey()).isEqualTo(clock.nowUtc().minusDays(29));
    clock.advanceBy(standardDays(29));
    save(reload());
    assertThat(reload().revisions).hasSize(3);
    assertThat(reload().revisions.firstKey()).isEqualTo(clock.nowUtc().minusDays(58));
    clock.advanceBy(standardDays(29));
    save(reload());
    assertThat(reload().revisions).hasSize(3);
    assertThat(reload().revisions.firstKey()).isEqualTo(clock.nowUtc().minusDays(58));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRawEntityLayout() {
    save(new TestObject());
    clock.advanceBy(standardDays(1));
    com.google.appengine.api.datastore.Entity entity =
        tm().transactNewReadOnly(() -> ofy().save().toEntity(reload()));
    assertThat(entity.getProperties().keySet()).containsExactly("revisions.key", "revisions.value");
    assertThat(entity.getProperties()).containsEntry(
        "revisions.key", ImmutableList.of(START_TIME.toDate(), START_TIME.plusDays(1).toDate()));
    assertThat(entity.getProperty("revisions.value")).isInstanceOf(List.class);
    assertThat(((List<Object>) entity.getProperty("revisions.value")).get(0))
        .isInstanceOf(com.google.appengine.api.datastore.Key.class);
  }

  @Test
  public void testLoad_neverSaved_returnsNull() {
    assertThat(ofy().load().entity(new TestObject()).now()).isNull();
  }

  @Test
  public void testLoad_missingRevisionRawProperties_createsEmptyObject() {
    com.google.appengine.api.datastore.Entity entity =
        tm().transactNewReadOnly(() -> ofy().save().toEntity(new TestObject()));
    entity.removeProperty("revisions.key");
    entity.removeProperty("revisions.value");
    TestObject object = ofy().load().fromEntity(entity);
    assertThat(object.revisions).isNotNull();
    assertThat(object.revisions).isEmpty();
  }
}
