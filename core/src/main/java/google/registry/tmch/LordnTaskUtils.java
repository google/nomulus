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

package google.registry.tmch;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Joiner;
import google.registry.model.domain.Domain;
import google.registry.model.registrar.Registrar;
import java.util.Optional;

/**
 * Helper methods for creating tasks containing CSV line data based on {@link Domain#getLordnPhase}.
 *
 * <p>Note that, per the <a href="https://tools.ietf.org/html/draft-ietf-regext-tmch-func-spec-04">
 * TMCH RFC</a>, while the application-datetime data is optional (which we never send because there
 * start-date sunrise has no applications), its presence in the header is still required.
 */
public final class LordnTaskUtils {

  public static final String COLUMNS_CLAIMS =
      "roid,domain-name,notice-id,registrar-id,"
          + "registration-datetime,ack-datetime,application-datetime";
  public static final String COLUMNS_SUNRISE =
      "roid,domain-name,SMD-id,registrar-id," + "registration-datetime,application-datetime";

  /** Returns the corresponding CSV LORDN line for a sunrise domain. */
  public static String getCsvLineForSunriseDomain(Domain domain) {
    return Joiner.on(',')
        .join(
            domain.getRepoId(),
            domain.getDomainName(),
            domain.getSmdId(),
            getIanaIdentifier(domain.getCreationRegistrarId()),
            domain.getCreationTime()); // Used as creation time.
  }

  /** Returns the corresponding CSV LORDN line for a claims domain. */
  public static String getCsvLineForClaimsDomain(Domain domain) {
    return Joiner.on(',')
        .join(
            domain.getRepoId(),
            domain.getDomainName(),
            domain.getLaunchNotice().getNoticeId().getTcnId(),
            getIanaIdentifier(domain.getCreationRegistrarId()),
            domain.getCreationTime(), // Used as creation time.
            domain.getLaunchNotice().getAcceptedTime());
  }

  /** Retrieves the IANA identifier for a registrar by its ID. */
  private static String getIanaIdentifier(String registrarId) {
    Optional<Registrar> registrar = Registrar.loadByRegistrarIdCached(registrarId);
    checkState(registrar.isPresent(), "No registrar found with ID: %s", registrarId);
    // Return the string "null" for null identifiers, since some Registrar.Types such as OTE will
    // have null iana ids.
    return String.valueOf(registrar.get().getIanaIdentifier());
  }

  private LordnTaskUtils() {}

  public enum LordnPhase {
    SUNRISE,
    CLAIMS,
    NONE
  }
}
