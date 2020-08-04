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

package google.registry.persistence.transaction;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class TransactionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestEntity.class)
          .withJpaUnitTestEntities(TestEntity.class)
          .build();

  TestEntity fooEntity, barEntity;

  public TransactionTest() {}

  @BeforeEach
  public void setUp() {
    fooEntity = new TestEntity("foo");
    barEntity = new TestEntity("bar");
  }

  @Test
  public void testTransactionReplay() {
    Transaction txn = new Transaction.Builder().addUpdate(fooEntity).addUpdate(barEntity).build();
    txn.writeToDatastore();

    ofyTm()
        .transact(
            () -> {
              assertThat(ofyTm().load(fooEntity.key())).isEqualTo(fooEntity);
              assertThat(ofyTm().load(barEntity.key())).isEqualTo(barEntity);
            });

    txn = new Transaction.Builder().addDelete(barEntity.key()).build();
    txn.writeToDatastore();
    assertThat(ofyTm().checkExists(barEntity.key())).isEqualTo(false);
  }

  @Test
  public void testSerialization() throws Exception {
    Transaction txn = new Transaction.Builder().addUpdate(barEntity).build();
    txn.writeToDatastore();

    txn = new Transaction.Builder().addUpdate(fooEntity).addDelete(barEntity.key()).build();
    txn = Transaction.deserialize(txn.serialize());

    txn.writeToDatastore();

    ofyTm()
        .transact(
            () -> {
              assertThat(ofyTm().load(fooEntity.key())).isEqualTo(fooEntity);
              assertThat(ofyTm().checkExists(barEntity.key())).isEqualTo(false);
            });
  }

  @Test
  public void testDeserializationErrors() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream out = new ObjectOutputStream(baos);
    out.writeInt(12345);
    out.close();
    assertThrows(IllegalArgumentException.class, () -> Transaction.deserialize(baos.toByteArray()));

    // Test with a short byte array.
    assertThrows(
        StreamCorruptedException.class, () -> Transaction.deserialize(new byte[] {1, 2, 3, 4}));
  }

  @Test
  public void testTransactionSerializationDisabledByDefault() {
    jpaTm()
        .transact(
            () -> {
              jpaTm().saveNew(fooEntity);
              jpaTm().saveNew(barEntity);
            });
    assertThat(
            jpaTm()
                .transact(() -> jpaTm().checkExists(VKey.createSql(TransactionEntity.class, 1L))))
        .isFalse();
  }

  @Entity(name = "TxnTestEntity")
  @javax.persistence.Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {
    @Id @javax.persistence.Id private String name;

    private TestEntity() {}

    private TestEntity(String name) {
      this.name = name;
    }

    public VKey<TestEntity> key() {
      return VKey.create(TestEntity.class, name, Key.create(this));
    }
  }
}
