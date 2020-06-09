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

package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.schema.replay.EntityTest.EntityForTesting;
import google.registry.testing.AppEngineRule;
import java.net.InetAddress;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.persistence.converter.InetAddressSetConverter}. */
public class InetAddressSetConverterTest {

  @RegisterExtension
  public final AppEngineRule appEngine =
      AppEngineRule.builder()
          .withDatastoreAndCloudSql()
          .withJpaUnitTestEntities(TestEntity.class)
          .build();

  @Test
  public void roundTripConversion_returnsSameAddresses() {
    verifySaveAndLoad(
        ImmutableSet.of(
            InetAddresses.forString("0.0.0.0"),
            InetAddresses.forString("192.168.0.1"),
            InetAddresses.forString("2001:41d0:1:a41e:0:0:0:1"),
            InetAddresses.forString("2041:0:140F::875B:131B")));
  }

  @Test
  public void roundTrip_emptySet() {
    verifySaveAndLoad(ImmutableSet.of());
  }

  @Test
  public void roundTrip_null() {
    verifySaveAndLoad(null);
  }

  private void verifySaveAndLoad(@Nullable Set<InetAddress> inetAddresses) {
    TestEntity testEntity = new TestEntity(inetAddresses);
    jpaTm().transact(() -> jpaTm().saveNew(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().load(VKey.createSql(TestEntity.class, "id")));
    assertThat(persisted.addresses).isEqualTo(inetAddresses);
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  @EntityForTesting
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    Set<InetAddress> addresses;

    private TestEntity() {}

    private TestEntity(Set<InetAddress> addresses) {
      this.addresses = addresses;
    }
  }
}
