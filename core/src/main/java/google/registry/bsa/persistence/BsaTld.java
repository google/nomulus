// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.persistence;

import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * A {@link Tld} that is ready to be included in BSA processing.
 *
 * <p>A {@code Tld}'s enrollment in BSA is controlled by configuration (See {@link
 * Tld#getBsaEnrollStartTime}). This table records the {@code Tld}s that have been initialized.
 */
@Entity
final class BsaTld {

  @Id String tld;

  /** The time when this tld was properly set up for BSA processing. */
  @SuppressWarnings("unused") // Field is for record and troubleshooting.
  @Column(nullable = false)
  Instant readyTime;

  @SuppressWarnings("unused") // For Hibernate
  private BsaTld() {}

  BsaTld(String tld, Instant readyTime) {
    this.tld = tld;
    this.readyTime = readyTime;
  }

  String getTld() {
    return tld;
  }

  static VKey<BsaTld> vKey(String tld) {
    return VKey.create(BsaTld.class, tld);
  }
}
