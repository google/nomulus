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
package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.domain.DomainBase;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.translators.VKeyTranslatorFactory;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.TestObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link VKey}. */
class VKeyTest {

  @RegisterExtension
  final AppEngineExtension appEngineExtension =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestObject.class)
          .build();

  @BeforeAll
  static void beforeAll() {
    VKeyTranslatorFactory.addTestEntityClass(TestObject.class);
  }

  @Test
  void testOptionalAccessors() {
    VKey<TestObject> key =
        VKey.create(TestObject.class, "foo", Key.create(TestObject.create("foo")));
    assertThat(key.maybeGetSqlKey().isPresent()).isTrue();
    assertThat(key.maybeGetOfyKey().isPresent()).isTrue();
    assertThat(VKey.createSql(TestObject.class, "foo").maybeGetSqlKey()).hasValue("foo");
  }

  @Test
  void testCreateById_failsWhenParentIsNullButShouldntBe() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> VKey.create(OneTime.class, 134L));
    assertThat(thrown).hasMessageThat().contains("BackupGroupRoot");
  }

  @Test
  void testCreateByName_failsWhenParentIsNullButShouldntBe() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> VKey.create(RegistrarContact.class, "fake@example.com"));
    assertThat(thrown).hasMessageThat().contains("BackupGroupRoot");
  }

  @Test
  void testRestoreOfy() {
    assertThat(VKey.restoreOfyFrom(null, TestObject.class, 100)).isNull();

    VKey<TestObject> key = VKey.createSql(TestObject.class, "foo");
    VKey<TestObject> restored = key.restoreOfy(TestObject.class, "bar");
    assertThat(restored.getOfyKey())
        .isEqualTo(Key.create(Key.create(TestObject.class, "bar"), TestObject.class, "foo"));
    assertThat(restored.getSqlKey()).isEqualTo("foo");

    assertThat(VKey.restoreOfyFrom(key).getOfyKey()).isEqualTo(Key.create(TestObject.class, "foo"));

    restored = key.restoreOfy(OtherObject.class, "baz", TestObject.class, "bar");
    assertThat(restored.getOfyKey())
        .isEqualTo(
            Key.create(
                Key.create(Key.create(OtherObject.class, "baz"), TestObject.class, "bar"),
                TestObject.class,
                "foo"));

    // Verify that we can use a key as the first argument.
    restored = key.restoreOfy(Key.create(TestObject.class, "bar"));
    assertThat(restored.getOfyKey())
        .isEqualTo(Key.create(Key.create(TestObject.class, "bar"), TestObject.class, "foo"));

    // Verify that we get an exception when a key is not the first argument.
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> key.restoreOfy(TestObject.class, "foo", Key.create(TestObject.class, "bar")));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Objectify keys may only be used for the first argument");

    // Verify other exception cases.
    thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> key.restoreOfy(TestObject.class, TestObject.class));
    assertThat(thrown)
        .hasMessageThat()
        .contains("class google.registry.testing.TestObject used as a key value.");

    thrown =
        assertThrows(IllegalArgumentException.class, () -> key.restoreOfy(TestObject.class, 1.5));
    assertThat(thrown).hasMessageThat().contains("Key value 1.5 must be a string or long.");

    thrown = assertThrows(IllegalArgumentException.class, () -> key.restoreOfy(TestObject.class));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Missing value for last key of type class google.registry.testing.TestObject");
  }

  @Test
  void testFromWebsafeKey() {
    // Creating an objectify key instead of a datastore key as this should get a correctly formatted
    // key path.  We have to one of our actual model object classes for this, TestObject can not be
    // reconstructed by the VKeyTranslatorFactory.
    DomainBase domain = newDomainBase("example.com", "ROID-1", persistActiveContact("contact-1"));
    Key<DomainBase> key = Key.create(domain);
    VKey<DomainBase> vkey = VKey.fromWebsafeKey(key.getString());
    assertThat(vkey.getKind()).isEqualTo(DomainBase.class);
    assertThat(vkey.getOfyKey()).isEqualTo(key);
    assertThat(vkey.getSqlKey()).isEqualTo("ROID-1");
  }

  /** Test stringify() with vkey created via different ways. */
  @Test
  void testStringify_sqlOnlyVKey() throws Exception {
    assertThat(VKey.createSql(TestObject.class, "foo").stringify())
        .isEqualTo("kind:google.registry.testing.TestObject@sql:rO0ABXQAA2Zvbw");
  }

  @Test
  void testStringify_ofyOnlyVKey() throws Exception {
    assertThat(VKey.createOfy(TestObject.class, Key.create(TestObject.class, "foo")).stringify())
        .isEqualTo(
            "kind:google.registry.testing.TestObject@ofy:agR0ZXN0chMLEgpUZXN0T2JqZWN0IgNmb28M");
  }

  @Test
  void testStringify_vkeyFromWebsafeKey() throws Exception {
    DomainBase domain = newDomainBase("example.com", "ROID-1", persistActiveContact("contact-1"));
    Key<DomainBase> key = Key.create(domain);
    VKey<DomainBase> vkey = VKey.fromWebsafeKey(key.getString());
    assertThat(vkey.stringify())
        .isEqualTo(
            "kind:google.registry.model.domain.DomainBas"
                + "e@sql:rO0ABXQABlJPSUQtMQ"
                + "@ofy:agR0ZXN0chYLEgpEb21haW5CYXNlIgZST0lELTEM");
  }

  @Test
  void testStringify_sqlAndOfyVKey() throws Exception {
    assertThat(
            VKey.create(TestObject.class, "foo", Key.create(TestObject.create("foo"))).stringify())
        .isEqualTo(
            "kind:google.registry.testing.TestObject@sql:rO0ABXQAA2Zvbw@ofy:agR0ZXN0cjELEg9FbnRpdH"
                + "lHcm91cFJvb3QiCWNyb3NzLXRsZAwLEgpUZXN0T2JqZWN0IgNmb28M");
  }

  @Test
  void testStringify_asymmetricVKey() throws Exception {
    assertThat(
            VKey.create(TestObject.class, "test", Key.create(TestObject.create("foo"))).stringify())
        .isEqualTo(
            "kind:google.registry.testing.TestObject@sql:rO0ABXQABHRlc3Q@ofy:agR0ZXN0cjELEg9FbnRpd"
                + "HlHcm91cFJvb3QiCWNyb3NzLXRsZAwLEgpUZXN0T2JqZWN0IgNmb28M");
  }

  /** Test create() via different vkey string representations. */
  @Test
  void testCreate_stringifedVKey_sqlOnlyVKeyString() throws Exception {
    assertThat(VKey.create("kind:google.registry.testing.TestObject@sql:rO0ABXQAA2Zvbw"))
        .isEqualTo(VKey.createSql(TestObject.class, "foo"));
  }

  @Test
  void testCreate_stringifedVKey_ofyOnlyVKeyString() throws Exception {
    assertThat(
            VKey.create(
                "kind:google.registry.testing.TestObject@ofy:agR0ZXN0chMLEgpUZXN0T2JqZWN0IgNmb28M"))
        .isEqualTo(VKey.createOfy(TestObject.class, Key.create(TestObject.class, "foo")));
  }

  @Test
  void testCreate_stringifedVKey_asymmetricVKeyString() throws Exception {
    assertThat(
            VKey.create(
                "kind:google.registry.testing.TestObject@sql:rO0ABXQABHRlc3Q@ofy:agR0ZXN0cjELEg9Fb"
                    + "nRpdHlHcm91cFJvb3QiCWNyb3NzLXRsZAwLEgpUZXN0T2JqZWN0IgNmb28M"))
        .isEqualTo(VKey.create(TestObject.class, "test", Key.create(TestObject.create("foo"))));
  }

  @Test
  void testCreate_stringifedVKey_sqlAndOfyVKeyString() throws Exception {
    assertThat(
            VKey.create(
                "kind:google.registry.testing.TestObject@sql:rO0ABXQAA2Zvbw@ofy:agR0ZXN0cjELEg9Fbn"
                    + "RpdHlHcm91cFJvb3QiCWNyb3NzLXRsZAwLEgpUZXN0T2JqZWN0IgNmb28M"))
        .isEqualTo(VKey.create(TestObject.class, "foo", Key.create(TestObject.create("foo"))));
  }

  @Test
  void testCreate_stringifyVkey_fromWebsafeKey() throws Exception {
    assertThat(
            VKey.create(
                "kind:google.registry.model.domain.DomainBase@sql:rO0ABXQABlJPSUQtMQ"
                    + "@ofy:agR0ZXN0chYLEgpEb21haW5CYXNlIgZST0lELTEM"))
        .isEqualTo(
            VKey.fromWebsafeKey(
                Key.create(
                        newDomainBase("example.com", "ROID-1", persistActiveContact("contact-1")))
                    .getString()));
  }

  @Test
  void testCreate_stringifedVKey_websafeKey() throws Exception {
    assertThat(VKey.create("agR0ZXN0chYLEgpEb21haW5CYXNlIgZST0lELTEM"))
        .isEqualTo(VKey.fromWebsafeKey("agR0ZXN0chYLEgpEb21haW5CYXNlIgZST0lELTEM"));
  }

  @Test
  void testCreate_invalidStringifiedVKey_failure() throws Exception {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> VKey.create("kind:google.registry.testing.TestObject@sq:l@ofya:bc"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot parse key string: kind:google.registry.testing.TestObject@sq:l@ofya:bc");
  }

  @Test
  void testCreate_invalidOfyKeyString_failure() throws Exception {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> VKey.create("invalid"));
    assertThat(thrown).hasMessageThat().contains("Could not parse Reference");
  }

  /** Test stringify() then create() flow. */
  @Test
  void testStringifyThenCreate_sqlOnlyVKey_testObject_stringKey_success() throws Exception {
    VKey<TestObject> vkey = VKey.createSql(TestObject.class, "foo");
    VKey<TestObject> newVkey = VKey.create(vkey.stringify());
    assertThat(newVkey).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_sqlOnlyVKey_testObject_longKey_success() throws Exception {
    VKey<TestObject> vkey = VKey.createSql(TestObject.class, (long) 12345);
    VKey<TestObject> newVkey = VKey.create(vkey.stringify());
    assertThat(newVkey).isEqualTo(vkey);
  }

  @Test
  void testCreate_createFromExistingOfyKey_success() throws Exception {
    String keyString =
        Key.create(newDomainBase("example.com", "ROID-1", persistActiveContact("contact-1")))
            .getString();
    assertThat(VKey.fromWebsafeKey(keyString)).isEqualTo(VKey.create(keyString));
  }

  @Test
  void testStringifyThenCreate_ofyOnlyVKey_testObject_success() throws Exception {
    VKey<TestObject> vkey =
        VKey.createOfy(TestObject.class, Key.create(TestObject.class, "tmpKey"));
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_ofyOnlyVKey_testObject_websafeString_success() throws Exception {
    VKey<TestObject> vkey = VKey.fromWebsafeKey(Key.create(TestObject.create("foo")).getString());
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_sqlAndOfyVKey_success() throws Exception {
    VKey<TestObject> vkey =
        VKey.create(TestObject.class, "foo", Key.create(TestObject.create("foo")));
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_asymmetricVKey_success() throws Exception {
    VKey<TestObject> vkey =
        VKey.create(TestObject.class, "sqlKey", Key.create(TestObject.create("foo")));
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_symmetricVKey_success() throws Exception {
    VKey<TestObject> vkey = TestObject.create("foo").key();
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Entity
  static class OtherObject {}
}
