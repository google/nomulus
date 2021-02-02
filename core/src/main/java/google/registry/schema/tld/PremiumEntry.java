// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.tld;

import google.registry.model.ImmutableObject;
import google.registry.model.registry.label.PremiumList;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Optional;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

/**
 * Entity class for the premium price of an individual domain label.
 *
 * <p>These are not persisted directly, but rather, using {@link PremiumList#getLabelsToPrices()}.
 */
@Entity
public class PremiumEntry extends ImmutableObject implements Serializable, SqlEntity {

  @Id
  @Column(nullable = false)
  Long revisionId;

  @Column(nullable = false)
  BigDecimal price;

  @Id
  @Column(nullable = false)
  String domainLabel;

  private PremiumEntry() {}

  @Override
  public Optional<DatastoreEntity> toDatastoreEntity() {
    return Optional.empty(); // PremiumList is dually-written
  }

  public BigDecimal getPrice() {
    return price;
  }

  public String getDomainLabel() {
    return domainLabel;
  }

  public static PremiumEntry create(BigDecimal price, String domainLabel) {
    PremiumEntry result = new PremiumEntry();
    result.price = price;
    result.domainLabel = domainLabel;
    return result;
  }
}
