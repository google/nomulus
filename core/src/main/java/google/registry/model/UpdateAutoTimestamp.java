// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** A timestamp that auto-updates on each save to Cloud SQL. */
@Embeddable
public class UpdateAutoTimestamp extends ImmutableObject implements UnsafeSerializable {

  @Column(name = "updateTimestamp")
  DateTime lastUpdateTime;

  // Unfortunately, we cannot use the @UpdateTimestamp annotation on "lastUpdateTime" in this class
  // because Hibernate does not allow it to be used on @Embeddable classes, see
  // https://hibernate.atlassian.net/browse/HHH-13235. This is a workaround.
  @PrePersist
  @PreUpdate
  void setTimestamp() {
    lastUpdateTime = tm().getTransactionTime();
  }

  /** Returns the timestamp, or {@code START_OF_TIME} if it's null. */
  public DateTime getTimestamp() {
    return Optional.ofNullable(lastUpdateTime).orElse(START_OF_TIME);
  }

  public static UpdateAutoTimestamp create(@Nullable DateTime timestamp) {
    UpdateAutoTimestamp instance = new UpdateAutoTimestamp();
    instance.lastUpdateTime = timestamp;
    return instance;
  }
}
