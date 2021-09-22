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

package google.registry.batch;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.flows.FlowUtils.marshalWithLenientRetry;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppController;
import google.registry.flows.EppRequestSource;
import google.registry.flows.PasswordOnlyTransportCredentials;
import google.registry.flows.StatelessRequestSessionMetadata;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.model.eppoutput.EppOutput;
import google.registry.persistence.transaction.QueryComposer.Comparator;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * An action that deletes all non-renewing domains whose expiration dates have now passed.
 *
 * <p>The registry runs on an autorenew domain model, so domains don't ever expire naturally; they
 * are only ever autorenewed. However, in some situations (such as URS) we don't want this to
 * happen. Thus, the domains are tagged as non-renewing and are deleted by the next daily invocation
 * of this action once they are past the date at which they were to expire.
 *
 * <p>Note that this action works by running a superuser EPP domain delete command, and as a side
 * effect of when domains are deleted (just past their expiration date), they are invariably in the
 * autorenew grace period when this happens.
 *
 * <p>Note also that the delete flow may fail in the uncommon case that a non-autorenewing domain
 * has a subordinate host. It is not trivial to handle this case automatically (as said host may be
 * in use by other domains), nor is it possible to take the correct action without exercising some
 * human judgment. Accordingly, such deletes will fail with SEVERE-level log messages every day when
 * this action runs, thus alerting us that human action is needed to correctly process the delete.
 */
@Action(
    service = Action.Service.BACKEND,
    path = DeleteExpiredDomainsAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class DeleteExpiredDomainsAction implements Runnable {

  public static final String PATH = "/_dr/task/deleteExpiredDomains";
  private static final String LOCK_NAME = "Delete expired domains";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final EppController eppController;
  private final String registryAdminClientId;
  private final Clock clock;
  private final LockHandler lockHandler;
  private final Response response;
  private final String deleteXmlTmpl;

  @Inject
  DeleteExpiredDomainsAction(
      EppController eppController,
      @Config("registryAdminClientId") String registryAdminClientId,
      Clock clock,
      LockHandler lockHandler,
      Response response) {
    this.eppController = eppController;
    this.registryAdminClientId = registryAdminClientId;
    this.clock = clock;
    this.lockHandler = lockHandler;
    this.response = response;
    this.deleteXmlTmpl =
        readResourceUtf8(DeleteExpiredDomainsAction.class, "delete_expired_domain.xml");
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
    DateTime runTime = clock.nowUtc();
    logger.atInfo().log(
        "Deleting non-renewing domains with autorenew end times up through %s.", runTime);

    // Note: in Datastore, this query is (and must be) non-transactional, and thus, is only
    // eventually consistent.
    ImmutableList<DomainBase> domainsToDelete =
        transactIfJpaTm(
            () ->
                tm().createQueryComposer(DomainBase.class)
                    .where("autorenewEndTime", Comparator.LTE, runTime)
                    .where("deletionTime", Comparator.EQ, END_OF_TIME)
                    .list());
    if (domainsToDelete.isEmpty()) {
      logger.atInfo().log("Found 0 domains to delete.");
      response.setPayload("Found 0 domains to delete.");
      return;
    }

    logger.atInfo().log(
        "Found %d domains to delete: %s.",
        domainsToDelete.size(),
        String.join(
            ", ",
            domainsToDelete.stream().map(DomainBase::getDomainName).collect(toImmutableList())));
    int successes = 0;
    for (DomainBase domain : domainsToDelete) {
      if (runDomainDeleteFlow(domain)) {
        successes++;
      }
    }
    int failures = domainsToDelete.size() - successes;
    String msg =
        String.format(
            "Finished; %d domains were successfully deleted and %d errored out.",
            successes, failures);
    logger.at(failures == 0 ? Level.INFO : Level.SEVERE).log(msg);
    response.setPayload(msg);
  }

  /** Runs the actual domain delete flow and returns whether the deletion was successful. */
  private boolean runDomainDeleteFlow(DomainBase domain) {
    logger.atInfo().log("Attempting to delete domain '%s'.", domain.getDomainName());
    // Create a new transaction that the flow's execution will be enlisted in that loads the domain
    // transactionally. This way we can ensure that nothing else has modified the domain in question
    // in the intervening period since the query above found it.
    Optional<EppOutput> eppOutput =
        tm().transact(
                () -> {
                  DomainBase transDomain = tm().loadByKey(domain.createVKey());
                  if (!domain.getAutorenewEndTime().isPresent()
                      || domain.getAutorenewEndTime().get().isAfter(tm().getTransactionTime())) {
                    logger.atSevere().log(
                        "Failed to delete domain %s because of its autorenew end time: %s.",
                        transDomain.getDomainName(), transDomain.getAutorenewEndTime());
                    return Optional.empty();
                  } else if (domain.getDeletionTime().isBefore(END_OF_TIME)) {
                    logger.atSevere().log(
                        "Failed to delete domain %s because it was already deleted on %s.",
                        transDomain.getDomainName(), transDomain.getDeletionTime());
                    return Optional.empty();
                  }
                  return Optional.of(
                      eppController.handleEppCommand(
                          new StatelessRequestSessionMetadata(
                              registryAdminClientId,
                              ProtocolDefinition.getVisibleServiceExtensionUris()),
                          new PasswordOnlyTransportCredentials(),
                          EppRequestSource.BACKEND,
                          false,
                          true,
                          deleteXmlTmpl
                              .replace("%DOMAIN%", transDomain.getDomainName())
                              .getBytes(UTF_8)));
                });

    if (eppOutput.isPresent()) {
      if (eppOutput.get().isSuccess()) {
        logger.atInfo().log("Successfully deleted domain '%s'.", domain.getDomainName());
      } else {
        logger.atSevere().log(
            "Failed to delete domain %s; EPP response:\n\n%s",
            domain.getDomainName(), new String(marshalWithLenientRetry(eppOutput.get()), UTF_8));
      }
    }
    return eppOutput.map(EppOutput::isSuccess).orElse(false);
  }
}
