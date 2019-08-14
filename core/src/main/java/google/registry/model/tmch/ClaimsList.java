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

package google.registry.model.tmch;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import javax.persistence.Table;

/** A list of TMCH claims labels and their associated claims keys. */
@Entity
@Table(name = "claims_list")
public class ClaimsList {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "revision_id")
  private Long revisionId;

  @Column(nullable = false)
  private ZonedDateTime creationTimestamp;

  @ElementCollection
  @CollectionTable(
      name = "claims_entry",
      joinColumns = @JoinColumn(name = "revision_id", referencedColumnName = "revision_id"))
  @MapKeyColumn(name = "domain_label", nullable = false)
  @Column(name = "claim_key", nullable = false)
  private Map<String, String> labelsToKeys;

  private ClaimsList(ZonedDateTime creationTimestamp, Map<String, String> labelsToKeys) {
    this.creationTimestamp = creationTimestamp;
    this.labelsToKeys = labelsToKeys;
  }

  // Hibernate requires this default constructor.
  private ClaimsList() {}

  /** Constructs a {@link ClaimsList} object. */
  public static ClaimsList create(
      ZonedDateTime creationTimestamp, Map<String, String> labelsToKeys) {
    return new ClaimsList(creationTimestamp, labelsToKeys);
  }

  /** Returns the revision id of this claims list if there is one, empty otherwise. */
  public Optional<Long> getRevisionId() {
    return Optional.ofNullable(revisionId);
  }

  /** Returns the creation time of this claims list. */
  public ZonedDateTime getCreationTimestamp() {
    return creationTimestamp;
  }

  /** Returns an {@link Map} mapping domain label to its lookup key. */
  public Map<String, String> getLabelsToKeys() {
    return labelsToKeys;
  }

  /** Returns the claim key for a given domain if there is one, empty otherwise. */
  public Optional<String> getClaimKey(String label) {
    return Optional.ofNullable(labelsToKeys.get(label));
  }
}
