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
// limitations under the License.

package google.registry.tmch;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.common.base.Joiner;
import google.registry.model.domain.DomainBase;
import google.registry.model.registrar.Registrar;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * Helper methods for creating tasks containing CSV line data in the lordn-sunrise and lordn-claims
 * queues based on DomainBase changes.
 *
 * <p>Note that, per the <a href="https://tools.ietf.org/html/draft-ietf-regext-tmch-func-spec-04">
 * TMCH RFC</a>, while the application-datetime data is optional (which we never send because there
 * start-date sunrise has no applications), its presence in the header is still required.
 */
public final class LordnTaskUtils {

  public static final String QUEUE_SUNRISE = "lordn-sunrise";
  public static final String QUEUE_CLAIMS = "lordn-claims";
  public static final String COLUMNS_CLAIMS = "roid,domain-name,notice-id,registrar-id,"
      + "registration-datetime,ack-datetime,application-datetime";
  public static final String COLUMNS_SUNRISE = "roid,domain-name,SMD-id,registrar-id,"
      + "registration-datetime,application-datetime";

  /**
   * Enqueues a task in the LORDN queue representing a line of CSV for LORDN export.
   */
  public static void enqueueDomainBaseTask(DomainBase domain) {
    tm().assertInTransaction();
    // This method needs to use ofy transactionTime as the DomainBase's creationTime because
    // CreationTime isn't yet populated when this method is called during the resource flow.
    String tld = domain.getTld();
    if (domain.getLaunchNotice() == null) {
      getQueue(QUEUE_SUNRISE).add(TaskOptions.Builder
          .withTag(tld)
          .method(Method.PULL)
          .payload(getCsvLineForSunriseDomain(domain, tm().getTransactionTime())));
    } else {
      getQueue(QUEUE_CLAIMS).add(TaskOptions.Builder
          .withTag(tld)
          .method(Method.PULL)
          .payload(getCsvLineForClaimsDomain(domain, tm().getTransactionTime())));
    }
  }

  /** Returns the corresponding CSV LORDN line for a sunrise domain. */
  public static String getCsvLineForSunriseDomain(DomainBase domain, DateTime transactionTime) {
    return Joiner.on(',')
        .join(
            domain.getRepoId(),
            domain.getFullyQualifiedDomainName(),
            domain.getSmdId(),
            getIanaIdentifier(domain.getCreationClientId()),
            transactionTime); // Used as creation time.
  }

  /** Returns the corresponding CSV LORDN line for a claims domain. */
  public static String getCsvLineForClaimsDomain(DomainBase domain, DateTime transactionTime) {
    return Joiner.on(',')
        .join(
            domain.getRepoId(),
            domain.getFullyQualifiedDomainName(),
            domain.getLaunchNotice().getNoticeId().getTcnId(),
            getIanaIdentifier(domain.getCreationClientId()),
            transactionTime, // Used as creation time.
            domain.getLaunchNotice().getAcceptedTime());
  }

  /** Retrieves the IANA identifier for a registrar based on the client id. */
  private static String getIanaIdentifier(String clientId) {
    Optional<Registrar> registrar = Registrar.loadByClientIdCached(clientId);
    checkState(registrar.isPresent(), "No registrar found for client id: %s", clientId);
    // Return the string "null" for null identifiers, since some Registrar.Types such as OTE will
    // have null iana ids.
    return String.valueOf(registrar.get().getIanaIdentifier());
  }

  private LordnTaskUtils() {}
}
