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

import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.util.Optional;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Object to be stored in the transaction table.
 *
 * <p>This consists of a sequential identifier and a serialized {@code Tranaction} object.
 */
@Entity
@Table(name = "Transaction")
public class TransactionEntity implements SqlEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private long id;

  private byte[] contents;

  TransactionEntity() {}

  TransactionEntity(byte[] contents) {
    this.contents = contents;
  }

  @Override
  public Optional<DatastoreEntity> toDatastoreEntity() {
    return Optional.empty(); // Not persisted in Datastore per se
  }

  public long getId() {
    return id;
  }

  public byte[] getContents() {
    return contents;
  }
}
