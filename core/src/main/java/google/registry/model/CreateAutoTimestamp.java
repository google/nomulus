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
// limitations under the Licenseschema..

package google.registry.model;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.gson.annotations.Expose;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** A timestamp that auto-updates when first saved to the database. */
@Embeddable
public class CreateAutoTimestamp extends ImmutableObject implements UnsafeSerializable {

  @Column(nullable = false)
  @Expose
  DateTime creationTime;

  @PrePersist
  @PreUpdate
  void setTimestamp() {
    if (creationTime == null) {
      creationTime = tm().getTransactionTime();
    }
  }

  /** Returns the timestamp. */
  @Nullable
  public DateTime getTimestamp() {
    return creationTime;
  }

  public static CreateAutoTimestamp create(@Nullable DateTime creationTime) {
    CreateAutoTimestamp instance = new CreateAutoTimestamp();
    instance.creationTime = creationTime;
    return instance;
  }
}
