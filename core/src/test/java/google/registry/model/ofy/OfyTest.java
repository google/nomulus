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

package google.registry.model.ofy;

import static com.google.appengine.api.datastore.DatastoreServiceFactory.getDatastoreService;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.getBaseEntityClassFromEntityOrKey;
import static google.registry.testing.DatabaseHelper.newContact;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.appengine.api.datastore.DatastoreFailureException;
import com.google.appengine.api.datastore.DatastoreTimeoutException;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.OnLoad;
import com.googlecode.objectify.annotation.OnSave;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactHistory;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.util.SystemClock;
import java.util.ConcurrentModificationException;
import java.util.function.Supplier;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for our wrapper around Objectify. */
@Disabled
public class OfyTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withClock(fakeClock).build();

  /** An entity to use in save and delete tests. */
  private HistoryEntry someObject;

  @BeforeEach
  void beforeEach() {
    someObject =
        new ContactHistory.Builder()
            .setRegistrarId("clientid")
            .setModificationTime(START_OF_TIME)
            .setType(HistoryEntry.Type.CONTACT_CREATE)
            .setContact(newContact("parentContact"))
            .setTrid(Trid.create("client", "server"))
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .build();
    // This can't be initialized earlier because namespaces need the AppEngineExtension to work.
  }

  @Test
  void testSavingKeyTwiceInOneCall() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ofy().transact(() -> auditedOfy().save().entities(someObject, someObject)));
  }

  /** Simple entity class with lifecycle callbacks. */
  @com.googlecode.objectify.annotation.Entity
  public static class LifecycleObject extends ImmutableObject {

    @Parent Key<?> parent = getCrossTldKey();

    @Id long id = 1;

    boolean onLoadCalled;
    boolean onSaveCalled;

    @OnLoad
    public void load() {
      onLoadCalled = true;
    }

    @OnSave
    public void save() {
      onSaveCalled = true;
    }
  }

  @Test
  void testLifecycleCallbacks_loadFromEntity() {
    auditedOfy().factory().register(LifecycleObject.class);
    LifecycleObject object = new LifecycleObject();
    Entity entity = auditedOfy().save().toEntity(object);
    assertThat(object.onSaveCalled).isTrue();
    assertThat(auditedOfy().load().<LifecycleObject>fromEntity(entity).onLoadCalled).isTrue();
  }

  @Test
  void testLifecycleCallbacks_loadFromDatastore() {
    auditedOfy().factory().register(LifecycleObject.class);
    final LifecycleObject object = new LifecycleObject();
    ofy().transact(() -> auditedOfy().save().entity(object).now());
    assertThat(object.onSaveCalled).isTrue();
    auditedOfy().clearSessionCache();
    assertThat(auditedOfy().load().entity(object).now().onLoadCalled).isTrue();
  }

  /** Avoid regressions of b/21309102 where transaction time did not change on each retry. */
  @Test
  void testTransact_getsNewTimestampOnEachTry() {
    ofy()
        .transact(
            new Runnable() {

              DateTime firstAttemptTime;

              @Override
              public void run() {
                if (firstAttemptTime == null) {
                  // Sleep a bit to ensure that the next attempt is at a new millisecond.
                  firstAttemptTime = ofy().getTransactionTime();
                  sleepUninterruptibly(java.time.Duration.ofMillis(10));
                  throw new ConcurrentModificationException();
                }
                assertThat(ofy().getTransactionTime()).isGreaterThan(firstAttemptTime);
              }
            });
  }

  @Test
  void testTransact_transientFailureException_retries() {
    assertThat(
            ofy()
                .transact(
                    new Supplier<Integer>() {

                      int count = 0;

                      @Override
                      public Integer get() {
                        count++;
                        if (count == 3) {
                          return count;
                        }
                        throw new TransientFailureException("");
                      }
                    }))
        .isEqualTo(3);
  }

  @Test
  void testTransact_datastoreTimeoutException_noManifest_retries() {
    assertThat(
            ofy()
                .transact(
                    new Supplier<Integer>() {

                      int count = 0;

                      @Override
                      public Integer get() {
                        // We don't write anything in this transaction, so there is no commit log
                        // manifest.
                        // Therefore it's always safe to retry since nothing got written.
                        count++;
                        if (count == 3) {
                          return count;
                        }
                        throw new DatastoreTimeoutException("");
                      }
                    }))
        .isEqualTo(3);
  }

  @Test
  void testTransact_datastoreTimeoutException_manifestNotWrittenToDatastore_retries() {
    assertThat(
            ofy()
                .transact(
                    new Supplier<Integer>() {

                      int count = 0;

                      @Override
                      public Integer get() {
                        // There will be something in the manifest now, but it won't be committed if
                        // we throw.
                        auditedOfy().save().entity(someObject.asHistoryEntry());
                        count++;
                        if (count == 3) {
                          return count;
                        }
                        throw new DatastoreTimeoutException("");
                      }
                    }))
        .isEqualTo(3);
  }

  @Test
  void testTransact_datastoreTimeoutException_manifestWrittenToDatastore_returnsSuccess() {
    // A work unit that throws if it is ever retried.
    Supplier work =
        new Supplier<Void>() {
          boolean firstCallToVrun = true;

          @Override
          public Void get() {
            if (firstCallToVrun) {
              firstCallToVrun = false;
              auditedOfy().save().entity(someObject.asHistoryEntry());
              return null;
            }
            fail("Shouldn't have retried.");
            return null;
          }
        };
    // A commit logged work that throws on the first attempt to get its result.
    CommitLoggedWork<Void> commitLoggedWork =
        new CommitLoggedWork<Void>(work, new SystemClock()) {
          boolean firstCallToGetResult = true;

          @Override
          public Void getResult() {
            if (firstCallToGetResult) {
              firstCallToGetResult = false;
              throw new DatastoreTimeoutException("");
            }
            return null;
          }
        };
    // Despite the DatastoreTimeoutException in the first call to getResult(), this should succeed
    // without retrying. If a retry is triggered, the test should fail due to the call to fail().
    auditedOfy().transactCommitLoggedWork(commitLoggedWork);
  }

  void doReadOnlyRetryTest(final RuntimeException e) {
    assertThat(
            ofy()
                .transactNewReadOnly(
                    new Supplier<Integer>() {

                      int count = 0;

                      @Override
                      public Integer get() {
                        count++;
                        if (count == 3) {
                          return count;
                        }
                        throw new TransientFailureException("");
                      }
                    }))
        .isEqualTo(3);
  }

  @Test
  void testTransactNewReadOnly_transientFailureException_retries() {
    doReadOnlyRetryTest(new TransientFailureException(""));
  }

  @Test
  void testTransactNewReadOnly_datastoreTimeoutException_retries() {
    doReadOnlyRetryTest(new DatastoreTimeoutException(""));
  }

  @Test
  void testTransactNewReadOnly_datastoreFailureException_retries() {
    doReadOnlyRetryTest(new DatastoreFailureException(""));
  }

  @Test
  void test_getBaseEntityClassFromEntityOrKey_regularEntity() {
    Contact contact = newContact("testcontact");
    assertThat(getBaseEntityClassFromEntityOrKey(contact)).isEqualTo(Contact.class);
    assertThat(getBaseEntityClassFromEntityOrKey(Key.create(contact))).isEqualTo(Contact.class);
  }

  @Test
  void test_getBaseEntityClassFromEntityOrKey_unregisteredEntity() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> getBaseEntityClassFromEntityOrKey(new SystemClock()));
    assertThat(thrown).hasMessageThat().contains("SystemClock");
  }

  @Test
  void test_getBaseEntityClassFromEntityOrKey_unregisteredEntityKey() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                getBaseEntityClassFromEntityOrKey(
                    Key.create(
                        com.google.appengine.api.datastore.KeyFactory.createKey(
                            "UnknownKind", 1))));
    assertThat(thrown).hasMessageThat().contains("UnknownKind");
  }

  @Test
  void test_doWithFreshSessionCache() {
    auditedOfy().saveWithoutBackup().entity(someObject.asHistoryEntry()).now();
    final HistoryEntry modifiedObject =
        someObject.asBuilder().setModificationTime(END_OF_TIME).build();
    // Mutate the saved objected, bypassing the Objectify session cache.
    getDatastoreService()
        .put(auditedOfy().saveWithoutBackup().toEntity(modifiedObject.asHistoryEntry()));
    // Normal loading should come from the session cache and shouldn't reflect the mutation.
    assertThat(auditedOfy().load().entity(someObject).now()).isEqualTo(someObject.asHistoryEntry());
    // Loading inside doWithFreshSessionCache() should reflect the mutation.
    boolean ran =
        auditedOfy()
            .doWithFreshSessionCache(
                () -> {
                  assertAboutImmutableObjects()
                      .that(auditedOfy().load().entity(someObject).now())
                      .isEqualExceptFields(modifiedObject, "contactBase");
                  return true;
                });
    assertThat(ran).isTrue();
    // Test the normal loading again to verify that we've restored the original session unchanged.
    assertThat(auditedOfy().load().entity(someObject).now()).isEqualTo(someObject.asHistoryEntry());
  }
}
