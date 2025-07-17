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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.net.InetAddress;
import java.util.Set;
import javax.annotation.Nullable;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link InetAddressSetUserType}. */
public class InetAddressSetUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withEntityClass(InetAddressSetTestEntity.class)
          .buildUnitTestExtension();

  @Test
  void roundTripConversion_returnsSameAddresses() {
    verifySaveAndLoad(
        ImmutableSet.of(
            InetAddresses.forString("0.0.0.0"),
            InetAddresses.forString("192.168.0.1"),
            InetAddresses.forString("2001:41d0:1:a41e:0:0:0:1"),
            InetAddresses.forString("2041:0:140F::875B:131B")));
  }

  @Test
  void roundTrip_emptySet() {
    verifySaveAndLoad(ImmutableSet.of());
  }

  @Test
  void roundTrip_null() {
    verifySaveAndLoad(null);
  }

  private void verifySaveAndLoad(@Nullable Set<InetAddress> inetAddresses) {
    InetAddressSetTestEntity testEntity = new InetAddressSetTestEntity(inetAddresses);
    persistResource(testEntity);
    InetAddressSetTestEntity persisted =
        tm().transact(() -> tm().loadByKey(VKey.create(InetAddressSetTestEntity.class, "id")));
    assertThat(persisted.addresses).isEqualTo(inetAddresses);
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class InetAddressSetTestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(InetAddressSetUserType.class)
    Set<InetAddress> addresses;

    private InetAddressSetTestEntity() {}

    private InetAddressSetTestEntity(Set<InetAddress> addresses) {
      this.addresses = addresses;
    }
  }
}
