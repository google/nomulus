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

package google.registry.testing;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.config.RegistryConfig.getContactAndHostRoidSuffix;
import static google.registry.model.EppResourceUtils.createDomainRepoId;
import static google.registry.model.EppResourceUtils.createRepoId;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.AppEngineRule.makeRegistrar1;
import static google.registry.testing.DatastoreHelper.newRegistry;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.host.HostResource;
import google.registry.model.registry.RegistryLockDao;
import google.registry.persistence.VKey;
import google.registry.schema.domain.RegistryLock;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.persistence.RollbackException;
import org.junit.function.ThrowingRunnable;

/** Static utils for setting up and retrieving test resources from the SQL database. */
public class SqlHelper {

  /** Counts of used ids for use in unit tests. Outside tests this is never used. */
  private static final AtomicLong NEXT_SQL_ID = new AtomicLong(1); // ids cannot be zero

  public static RegistryLock saveRegistryLock(RegistryLock lock) {
    return jpaTm().transact(() -> RegistryLockDao.save(lock));
  }

  public static Optional<RegistryLock> getRegistryLockByVerificationCode(String verificationCode) {
    return jpaTm().transact(() -> RegistryLockDao.getByVerificationCode(verificationCode));
  }

  public static Optional<RegistryLock> getMostRecentRegistryLockByRepoId(String repoId) {
    return jpaTm().transact(() -> RegistryLockDao.getMostRecentByRepoId(repoId));
  }

  public static Optional<RegistryLock> getMostRecentVerifiedRegistryLockByRepoId(String repoId) {
    return jpaTm().transact(() -> RegistryLockDao.getMostRecentVerifiedLockByRepoId(repoId));
  }

  public static Optional<RegistryLock> getMostRecentUnlockedRegistryLockByRepoId(String repoId) {
    return jpaTm().transact(() -> RegistryLockDao.getMostRecentVerifiedUnlockByRepoId(repoId));
  }

  public static ImmutableList<RegistryLock> getRegistryLocksByRegistrarId(String registrarId) {
    return jpaTm().transact(() -> RegistryLockDao.getLocksByRegistrarId(registrarId));
  }

  public static Optional<RegistryLock> getRegistryLockByRevisionId(long revisionId) {
    return jpaTm().transact(() -> RegistryLockDao.getByRevisionId(revisionId));
  }

  public static void saveTld(String tldStr) {
    jpaTm().transact(() -> jpaTm().saveNew(newRegistry(tldStr, "")));
  }

  public static void saveRegistrar(String clientId) {
    jpaTm()
        .transact(
            () -> jpaTm().saveNew(makeRegistrar1().asBuilder().setClientId(clientId).build()));
  }

  public static DomainBase newDomain(String domainName) {
    String repoId = generateNewDomainRoid(getTldFromDomainName(domainName));
    return newDomain(domainName, repoId, persistActiveContact("contact1234"));
  }

  public static DomainBase newDomain(String domainName, ContactResource contact) {
    return newDomain(domainName, generateNewDomainRoid(getTldFromDomainName(domainName)), contact);
  }

  public static DomainBase newDomain(String domainName, HostResource host) {
    return newDomain(domainName)
        .asBuilder()
        .setNameservers(ImmutableSet.of(host.createKey()))
        .build();
  }

  public static DomainBase newDomain(String domainName, String repoId, ContactResource contact) {
    VKey<ContactResource> contactKey = contact.createVKey();
    return new DomainBase.Builder()
        .setRepoId(repoId)
        .setFullyQualifiedDomainName(domainName)
        .setCreationClientId("TheRegistrar")
        .setPersistedCurrentSponsorClientId("TheRegistrar")
        .setCreationTimeForTest(START_OF_TIME)
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .setRegistrant(contactKey)
        .setContacts(
            ImmutableSet.of(
                DesignatedContact.create(DesignatedContact.Type.ADMIN, contactKey),
                DesignatedContact.create(DesignatedContact.Type.TECH, contactKey)))
        .setRegistrationExpirationTime(END_OF_TIME)
        .build();
  }

  public static ContactResource persistActiveContact(String contactId) {
    return persistResource(newContactResource(contactId));
  }

  public static ContactResource newContactResource(String contactId) {
    return newContactResourceWithRoid(contactId, generateNewContactHostRoid());
  }

  public static ContactResource newContactResourceWithRoid(String contactId, String repoId) {
    return new ContactResource.Builder()
        .setRepoId(repoId)
        .setContactId(contactId)
        .setCreationClientId("TheRegistrar")
        .setPersistedCurrentSponsorClientId("TheRegistrar")
        .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .setCreationTimeForTest(START_OF_TIME)
        .build();
  }

  public static String generateNewContactHostRoid() {
    return createRepoId(NEXT_SQL_ID.getAndIncrement(), getContactAndHostRoidSuffix());
  }

  public static <R extends EppResource> R persistResource(final R resource) {
    assertWithMessage("Attempting to persist a Builder is almost certainly an error in test code")
        .that(resource)
        .isNotInstanceOf(Buildable.Builder.class);
    jpaTm().transact(() -> jpaTm().saveNewOrUpdate(resource));
    return jpaTm()
        .transact(
            () ->
                jpaTm().load(VKey.createSql((Class<R>) resource.getClass(), resource.getRepoId())));
  }

  public static void assertThrowForeignKeyViolation(ThrowingRunnable runnable) {
    RollbackException thrown = assertThrows(RollbackException.class, runnable);
    assertThat(Throwables.getRootCause(thrown)).isInstanceOf(SQLException.class);
    assertThat(Throwables.getRootCause(thrown))
        .hasMessageThat()
        .contains("violates foreign key constraint");
  }

  /** Returns a newly allocated, globally unique domain repoId of the format HEX-TLD. */
  public static String generateNewDomainRoid(String tld) {
    return createDomainRepoId(NEXT_SQL_ID.getAndIncrement(), tld);
  }

  private SqlHelper() {}
}
