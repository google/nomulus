// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import google.registry.model.ImmutableObject;
import google.registry.model.replay.NonReplicatedEntity;
import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

/**
 * Claims entry record, used by ClaimsList for persistence.
 *
 * <p>It would be preferable to have this nested in {@link ClaimsList}, but for some reason
 * hibernate won't generate this into the schema in this case. We may not care, as we only use the
 * generated schema for informational purposes and persistence against the actual schema seems to
 * work.
 */
@Entity(name = "ClaimsEntry")
class ClaimsEntry extends ImmutableObject implements NonReplicatedEntity, Serializable {
  @Id private Long revisionId;
  @Id private String domainLabel;

  @Column(nullable = false)
  private String claimKey;

  /** Default constructor for Hibernate. */
  ClaimsEntry() {}

  ClaimsEntry(Long revisionId, String domainLabel, String claimKey) {
    this.revisionId = revisionId;
    this.domainLabel = domainLabel;
    this.claimKey = claimKey;
  }

  String getDomainLabel() {
    return domainLabel;
  }

  String getClaimKey() {
    return claimKey;
  }
}
