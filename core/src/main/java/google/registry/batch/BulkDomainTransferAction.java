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

package google.registry.batch;

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.flows.FlowUtils.marshalWithLenientRetry;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.RateLimiter;
import google.registry.flows.EppController;
import google.registry.flows.EppRequestSource;
import google.registry.flows.PasswordOnlyTransportCredentials;
import google.registry.flows.StatelessRequestSessionMetadata;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.EppOutput;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.util.DateTimeUtils;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import org.joda.time.Duration;

/**
 * An action that transfers a set of domains from one registrar to another.
 *
 * <p>This should be used as part of the BTAPPA (Bulk Transfer After a Partial Portfolio
 * Acquisition) process in order to transfer a (possibly large) list of domains from one registrar
 * to another, though it may be used in other situations as well.
 *
 * <p>The body of the HTTP post request should be a JSON list of the domains to be transferred.
 * Because the list of domains to process can be quite large, this action should be called by a tool
 * that batches the list of domains into reasonable sizes if necessary. The recommended usage path
 * is to call this through the {@link google.registry.tools.BulkDomainTransferCommand}, which
 * handles batching and input handling.
 *
 * <p>This runs as a single-threaded idempotent action that runs a superuser domain transfer on each
 * domain to process. We go through the standard EPP process to make sure that we have an accurate
 * historical representation of events (rather than force-modifying the domains in place).
 *
 * <p>Consider passing in an "maxQps" parameter based on the number of domains being transferred,
 * otherwise the default is {@link BatchModule#DEFAULT_MAX_QPS}.
 */
@Action(
    service = Action.Service.BACKEND,
    path = BulkDomainTransferAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_ADMIN)
public class BulkDomainTransferAction implements Runnable {

  public static final String PATH = "/_dr/task/bulkDomainTransfer";

  private static final String SUPERUSER_TRANSFER_XML_FORMAT =
"""
<epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
  <command>
    <transfer op="request">
      <domain:transfer xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
        <domain:name>%DOMAIN_NAME%</domain:name>
      </domain:transfer>
    </transfer>
    <extension>
      <superuser:domainTransferRequest xmlns:superuser="urn:google:params:xml:ns:superuser-1.0">
        <superuser:renewalPeriod unit="y">0</superuser:renewalPeriod>
        <superuser:automaticTransferLength>0</superuser:automaticTransferLength>
      </superuser:domainTransferRequest>
      <metadata:metadata xmlns:metadata="urn:google:params:xml:ns:metadata-1.0">
        <metadata:reason>%REASON%</metadata:reason>
        <metadata:requestedByRegistrar>%REQUESTED_BY_REGISTRAR%</metadata:requestedByRegistrar>
      </metadata:metadata>
    </extension>
    <clTRID>BulkDomainTransferAction</clTRID>
  </command>
</epp>
""";

  private static final String LOCK_NAME = "Domain bulk transfer";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final EppController eppController;
  private final LockHandler lockHandler;
  private final RateLimiter rateLimiter;
  private final ImmutableList<String> bulkTransferDomainNames;
  private final String gainingRegistrarId;
  private final String losingRegistrarId;
  private final boolean requestedByRegistrar;
  private final String reason;
  private final Response response;

  private int successes = 0;
  private int alreadyTransferred = 0;
  private int pendingDelete = 0;
  private int missingDomains = 0;
  private int errors = 0;

  @Inject
  BulkDomainTransferAction(
      EppController eppController,
      LockHandler lockHandler,
      @Named("standardRateLimiter") RateLimiter rateLimiter,
      @Parameter("bulkTransferDomainNames") ImmutableList<String> bulkTransferDomainNames,
      @Parameter("gainingRegistrarId") String gainingRegistrarId,
      @Parameter("losingRegistrarId") String losingRegistrarId,
      @Parameter("requestedByRegistrar") boolean requestedByRegistrar,
      @Parameter("reason") String reason,
      Response response) {
    this.eppController = eppController;
    this.lockHandler = lockHandler;
    this.rateLimiter = rateLimiter;
    this.bulkTransferDomainNames = bulkTransferDomainNames;
    this.gainingRegistrarId = gainingRegistrarId;
    this.losingRegistrarId = losingRegistrarId;
    this.requestedByRegistrar = requestedByRegistrar;
    this.reason = reason;
    this.response = response;
  }

  @Override
  public void run() {
    response.setContentType(PLAIN_TEXT_UTF_8);
    Callable<Void> runner =
        () -> {
          try {
            runLocked();
            response.setStatus(SC_OK);
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Errored out during execution.");
            response.setStatus(SC_INTERNAL_SERVER_ERROR);
            response.setPayload(String.format("Errored out with cause: %s", e));
          }
          return null;
        };

    if (!lockHandler.executeWithLocks(runner, null, Duration.standardHours(1), LOCK_NAME)) {
      // Send a 200-series status code to prevent this conflicting action from retrying.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload("Could not acquire lock; already running?");
    }
  }

  private void runLocked() {
    logger.atInfo().log("Attempting to transfer %d domains.", bulkTransferDomainNames.size());
    for (String domainName : bulkTransferDomainNames) {
      rateLimiter.acquire();
      tm().transact(() -> runTransferFlowInTransaction(domainName));
    }

    String msg =
        String.format(
            "Finished; %d domains were successfully transferred, %d were previously transferred, %s"
                + " were missing domains, %s are pending delete, and %d errored out.",
            successes, alreadyTransferred, missingDomains, pendingDelete, errors);
    logger.at(errors + missingDomains == 0 ? Level.INFO : Level.WARNING).log(msg);
    response.setPayload(msg);
  }

  private void runTransferFlowInTransaction(String domainName) {
    if (shouldSkipDomain(domainName)) {
      return;
    }
    String xml =
        SUPERUSER_TRANSFER_XML_FORMAT
            .replace("%DOMAIN_NAME%", domainName)
            .replace("%REASON%", reason)
            .replace("%REQUESTED_BY_REGISTRAR%", String.valueOf(requestedByRegistrar));
    EppOutput output =
        eppController.handleEppCommand(
            new StatelessRequestSessionMetadata(
                gainingRegistrarId, ProtocolDefinition.getVisibleServiceExtensionUris()),
            new PasswordOnlyTransportCredentials(),
            EppRequestSource.TOOL,
            false,
            true,
            xml.getBytes(US_ASCII));
    if (output.isSuccess()) {
      logger.atInfo().log("Successfully transferred domain '%s'.", domainName);
      successes++;
    } else {
      logger.atWarning().log(
          "Failed transferring domain '%s' with error '%s'.",
          domainName, new String(marshalWithLenientRetry(output), US_ASCII));
      errors++;
    }
  }

  private boolean shouldSkipDomain(String domainName) {
    Optional<Domain> maybeDomain =
        ForeignKeyUtils.loadResource(Domain.class, domainName, tm().getTransactionTime());
    if (maybeDomain.isEmpty()) {
      logger.atWarning().log("Domain '%s' was already deleted", domainName);
      missingDomains++;
      return true;
    }
    Domain domain = maybeDomain.get();
    String currentRegistrarId = domain.getCurrentSponsorRegistrarId();
    if (currentRegistrarId.equals(gainingRegistrarId)) {
      logger.atInfo().log("Domain '%s' was already transferred", domainName);
      alreadyTransferred++;
      return true;
    }
    if (!currentRegistrarId.equals(losingRegistrarId)) {
      logger.atWarning().log(
          "Domain '%s' had unexpected registrar '%s'", domainName, currentRegistrarId);
      errors++;
      return true;
    }
    if (domain.getStatusValues().contains(StatusValue.PENDING_DELETE)
        || !domain.getDeletionTime().equals(DateTimeUtils.END_OF_TIME)) {
      logger.atWarning().log("Domain '%s' is in PENDING_DELETE", domainName);
      pendingDelete++;
      return true;
    }
    return false;
  }
}
