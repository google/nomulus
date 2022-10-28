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
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.common.ClassPathManager;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.TestObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link VKey}. */
class VKeyTest {

  @RegisterExtension
  final AppEngineExtension appEngineExtension =
      AppEngineExtension.builder().withCloudSql().withOfyTestEntities(TestObject.class).build();

  @BeforeAll
  static void beforeAll() {
    ClassPathManager.addTestEntityClass(TestObject.class);
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
    assertThat(thrown).hasMessageThat().contains("UpdateAutoTimestampEntity");
  }

  @Test
  void testCreateByName_failsWhenParentIsNullButShouldntBe() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> VKey.create(RegistrarPoc.class, "fake@example.com"));
    assertThat(thrown).hasMessageThat().contains("UpdateAutoTimestampEntity");
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
    Domain domain = newDomain("example.com", "ROID-1", persistActiveContact("contact-1"));
    Key<Domain> key = Key.create(domain);
    VKey<Domain> vkey = VKey.fromWebsafeKey(key.getString());
    assertThat(vkey.getKind()).isEqualTo(Domain.class);
    assertThat(vkey.getOfyKey()).isEqualTo(key);
    assertThat(vkey.getSqlKey()).isEqualTo("ROID-1");
  }

  /** Test stringify() with vkey created via different ways. */
  @Test
  void testStringify_sqlOnlyVKey() {
    assertThat(VKey.createSql(TestObject.class, "foo").stringify())
        .isEqualTo("kind:TestObject@sql:rO0ABXQAA2Zvbw");
  }

  @Test
  void testStringify_ofyOnlyVKey() {
    assertThat(VKey.createOfy(TestObject.class, Key.create(TestObject.class, "foo")).stringify())
        .isEqualTo("kind:TestObject@ofy:agR0ZXN0chMLEgpUZXN0T2JqZWN0IgNmb28M");
  }

  @Test
  void testStringify_vkeyFromWebsafeKey() {
    Domain domain = newDomain("example.com", "ROID-1", persistActiveContact("contact-1"));
    Key<Domain> key = Key.create(domain);
    VKey<Domain> vkey = VKey.fromWebsafeKey(key.getString());
    assertThat(vkey.stringify())
        .isEqualTo(
            "kind:Domain" + "@sql:rO0ABXQABlJPSUQtMQ" + "@ofy:agR0ZXN0chILEgZEb21haW4iBlJPSUQtMQw");
  }

  @Test
  void testStringify_sqlAndOfyVKey() {
    assertThat(
            VKey.create(TestObject.class, "foo", Key.create(TestObject.create("foo"))).stringify())
        .isEqualTo("kind:TestObject@sql:rO0ABXQAA2Zvbw@ofy:agR0ZXN0chMLEgpUZXN0T2JqZWN0IgNmb28M");
  }

  @Test
  void testStringify_asymmetricVKey() {
    assertThat(
            VKey.create(TestObject.class, "test", Key.create(TestObject.create("foo"))).stringify())
        .isEqualTo("kind:TestObject@sql:rO0ABXQABHRlc3Q@ofy:agR0ZXN0chMLEgpUZXN0T2JqZWN0IgNmb28M");
  }

  /** Test create() via different vkey string representations. */
  @Test
  void testCreate_stringifedVKey_sqlOnlyVKeyString() {
    assertThat(VKey.create("kind:TestObject@sql:rO0ABXQAA2Zvbw"))
        .isEqualTo(VKey.createSql(TestObject.class, "foo"));
  }

  @Test
  void testCreate_stringifiedVKey_resourceKeyFromTaskQueue() throws Exception {
    VKey<Host> vkeyFromNewWebsafeKey =
        VKey.create(
            "kind:Host@sql:rO0ABXQADzZCQjJGNDc2LUdPT0dMRQ@ofy:ahdzfm"
                + "RvbWFpbi1yZWdpc3RyeS1hbHBoYXIhCxIMSG9zdFJlc291cmNlIg82QkIyRjQ3Ni1HT09HTEUM");

    assertThat(vkeyFromNewWebsafeKey.getSqlKey()).isEqualTo("6BB2F476-GOOGLE");
    assertThat(vkeyFromNewWebsafeKey.getOfyKey().getString())
        .isEqualTo(
            "ahdzfmRvbWFpbi1yZWdpc3RyeS1hbHBoYXIhCxIMSG9zdFJlc291cmNlIg82QkIyRjQ3Ni1HT09HTEUM");
  }

  @Test
  void testCreate_stringifedVKey_ofyOnlyVKeyString() {
    assertThat(VKey.create("kind:TestObject@ofy:agR0ZXN0chMLEgpUZXN0T2JqZWN0IgNmb28M"))
        .isEqualTo(VKey.createOfy(TestObject.class, Key.create(TestObject.class, "foo")));
  }

  @Test
  void testCreate_stringifedVKey_asymmetricVKeyString() {
    assertThat(
            VKey.create(
                "kind:TestObject@sql:rO0ABXQABHRlc3Q@ofy:agR0ZXN0cjELEg9Fb"
                    + "nRpdHlHcm91cFJvb3QiCWNyb3NzLXRsZAwLEgpUZXN0T2JqZWN0IgNmb28M"))
        .isEqualTo(VKey.create(TestObject.class, "test", Key.create(TestObject.create("foo"))));
  }

  @Test
  void testCreate_stringifedVKey_sqlAndOfyVKeyString() {
    assertThat(
            VKey.create(
                "kind:TestObject@sql:rO0ABXQAA2Zvbw@ofy:agR0ZXN0cjELEg9Fbn"
                    + "RpdHlHcm91cFJvb3QiCWNyb3NzLXRsZAwLEgpUZXN0T2JqZWN0IgNmb28M"))
        .isEqualTo(VKey.create(TestObject.class, "foo", Key.create(TestObject.create("foo"))));
  }

  @Test
  void testCreate_stringifyVkey_fromWebsafeKey() {
    assertThat(
            VKey.create(
                "kind:Domain@sql:rO0ABXQABlJPSUQtMQ"
                    + "@ofy:agR0ZXN0chYLEgpEb21haW5CYXNlIgZST0lELTEM"))
        .isEqualTo(
            VKey.fromWebsafeKey(
                Key.create(newDomain("example.com", "ROID-1", persistActiveContact("contact-1")))
                    .getString()));
  }

  @Test
  void testCreate_stringifedVKey_websafeKey() {
    assertThat(VKey.create("agR0ZXN0chkLEgZEb21haW4iDUdBU0RHSDQyMkQtSUQM"))
        .isEqualTo(VKey.fromWebsafeKey("agR0ZXN0chkLEgZEb21haW4iDUdBU0RHSDQyMkQtSUQM"));
  }

  @Test
  void testCreate_invalidStringifiedVKey_failure() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> VKey.create("kind:TestObject@sq:l@ofya:bc"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot parse key string: kind:TestObject@sq:l@ofya:bc");
  }

  @Test
  void testCreate_invalidOfyKeyString_failure() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> VKey.create("invalid"));
    assertThat(thrown).hasMessageThat().contains("Could not parse Reference");
  }

  /** Test stringify() then create() flow. */
  @Test
  void testStringifyThenCreate_sqlOnlyVKey_testObject_stringKey_success() {
    VKey<TestObject> vkey = VKey.createSql(TestObject.class, "foo");
    VKey<TestObject> newVkey = VKey.create(vkey.stringify());
    assertThat(newVkey).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_sqlOnlyVKey_testObject_longKey_success() {
    VKey<TestObject> vkey = VKey.createSql(TestObject.class, (long) 12345);
    VKey<TestObject> newVkey = VKey.create(vkey.stringify());
    assertThat(newVkey).isEqualTo(vkey);
  }

  @Test
  void testCreate_createFromExistingOfyKey_success() {
    String keyString =
        Key.create(newDomain("example.com", "ROID-1", persistActiveContact("contact-1")))
            .getString();
    assertThat(VKey.fromWebsafeKey(keyString)).isEqualTo(VKey.create(keyString));
  }

  @Test
  void testStringifyThenCreate_ofyOnlyVKey_testObject_success() {
    VKey<TestObject> vkey =
        VKey.createOfy(TestObject.class, Key.create(TestObject.class, "tmpKey"));
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_ofyOnlyVKey_testObject_websafeString_success() {
    VKey<TestObject> vkey = VKey.fromWebsafeKey(Key.create(TestObject.create("foo")).getString());
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_sqlAndOfyVKey_success() {
    VKey<TestObject> vkey =
        VKey.create(TestObject.class, "foo", Key.create(TestObject.create("foo")));
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_asymmetricVKey_success() {
    VKey<TestObject> vkey =
        VKey.create(TestObject.class, "sqlKey", Key.create(TestObject.create("foo")));
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Test
  void testStringifyThenCreate_symmetricVKey_success() {
    VKey<TestObject> vkey = TestObject.create("foo").key();
    assertThat(VKey.create(vkey.stringify())).isEqualTo(vkey);
  }

  @Test
  void testToString_sqlOnlyVKey() {
    assertThat(VKey.createSql(TestObject.class, "testId").toString())
        .isEqualTo("VKey<TestObject>(sql:testId)");
  }

  @Test
  void testToString_ofyOnlyVKey_withName() {
    assertThat(
            VKey.createOfy(TestObject.class, Key.create(TestObject.class, "testName")).toString())
        .isEqualTo("VKey<TestObject>(ofy:testName)");
  }

  @Test
  void testToString_ofyOnlyVKey_withId() {
    assertThat(VKey.createOfy(TestObject.class, Key.create(TestObject.class, 12345)).toString())
        .isEqualTo("VKey<TestObject>(ofy:12345)");
  }

  @Test
  void testToString_sqlAndOfyVKey() {
    assertThat(
            VKey.create(TestObject.class, "foo", Key.create(TestObject.create("ofy"))).toString())
        .isEqualTo("VKey<TestObject>(sql:foo,ofy:ofy)");
  }

  @Entity
  static class OtherObject {}
}
