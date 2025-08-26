// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.bsa.BsaTransactions.bsaQuery;
import static google.registry.bsa.ReservedDomainsUtils.getAllReservedNames;
import static google.registry.bsa.ReservedDomainsUtils.isReservedDomain;
import static google.registry.bsa.persistence.BsaLabelUtils.isLabelBlocked;
import static google.registry.bsa.persistence.Queries.batchReadBsaLabelText;
import static google.registry.bsa.persistence.Queries.queryMissedRegisteredUnblockables;
import static google.registry.bsa.persistence.Queries.queryUnblockableDomainByLabels;
import static google.registry.model.tld.Tld.isEnrolledWithBsa;
import static google.registry.model.tld.Tlds.getTldEntitiesOfType;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.BatchedStreams.toBatches;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InternetDomainName;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.api.UnblockableDomain.Reason;
import google.registry.bsa.persistence.DownloadScheduler;
import google.registry.bsa.persistence.Queries;
import google.registry.bsa.persistence.Queries.DomainLifeSpan;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Validates the BSA data in the database against the most recent block lists. */
@Action(
    service = GaeService.BSA,
    path = BsaValidateAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_ADMIN)
public class BsaValidateAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/bsaValidate";
  private final GcsClient gcsClient;
  private final IdnChecker idnChecker;
  private final BsaEmailSender emailSender;
  private final int transactionBatchSize;
  private final Duration maxStaleness;
  private final Clock clock;
  private final Response response;

  @Inject
  BsaValidateAction(
      GcsClient gcsClient,
      IdnChecker idnChecker,
      BsaEmailSender emailSender,
      @Config("bsaTxnBatchSize") int transactionBatchSize,
      @Config("bsaValidationMaxStaleness") Duration maxStaleness,
      Clock clock,
      Response response) {
    this.gcsClient = gcsClient;
    this.idnChecker = idnChecker;
    this.emailSender = emailSender;
    this.transactionBatchSize = transactionBatchSize;
    this.maxStaleness = maxStaleness;
    this.clock = clock;
    this.response = response;
  }

  @Override
  public void run() {
    try {
      validate();
    } catch (Throwable throwable) {
      logger.atWarning().withCause(throwable).log("Failed to validate block lists.");
      emailSender.sendNotification("BSA validation aborted", getStackTraceAsString(throwable));
    }
    // Always return OK. No need to retry since all queries and GCS accesses are already
    // implicitly retried.
    response.setStatus(SC_OK);
  }

  /** Performs validation of BSA data in the database. */
  void validate() {
    Optional<String> downloadJobName =
        bsaQuery(DownloadScheduler::fetchMostRecentDownloadJobIdIfCompleted);
    if (downloadJobName.isEmpty()) {
      logger.atInfo().log("Cannot validate: block list downloads not found.");
      emailSender.sendNotification(
          "BSA validation does not run: block list downloads not found", "");
      return;
    }
    logger.atInfo().log("Validating BSA with latest download: %s", downloadJobName.get());

    ImmutableList.Builder<String> errorsBuilder = new ImmutableList.Builder<>();
    errorsBuilder.addAll(checkBsaLabels(downloadJobName.get()));
    errorsBuilder.addAll(checkWronglyReportedUnblockableDomains());
    errorsBuilder.addAll(checkMissingUnblockableDomains());

    ImmutableList<String> errors = errorsBuilder.build();

    String resultSummary =
        errors.isEmpty()
            ? "BSA validation completed: no errors found"
            : "BSA validation completed with errors";
    if (!errors.isEmpty()) {
      emailValidationResults(resultSummary, downloadJobName.get(), errors);
    }
    logger.atInfo().log("%s (latest download: %s)", resultSummary, downloadJobName.get());
  }

  void emailValidationResults(String subject, String jobName, ImmutableList<String> results) {
    String body =
        String.format("Most recent download is %s.\n\n", jobName) + Joiner.on('\n').join(results);
    emailSender.sendNotification(subject, body);
  }

  ImmutableList<String> checkBsaLabels(String jobName) {
    ImmutableSet<String> downloadedLabels = fetchDownloadedLabels(jobName);
    ImmutableSet<String> persistedLabels = fetchPersistedLabels(transactionBatchSize);
    ImmutableList.Builder<String> errors = new ImmutableList.Builder<>();

    int nErrorExamples = 10;
    SetView<String> missingLabels = Sets.difference(downloadedLabels, persistedLabels);
    if (!missingLabels.isEmpty()) {
      String examples = Joiner.on(',').join(Iterables.limit(missingLabels, nErrorExamples));
      String errorMessage =
          String.format(
              "Found %d missing labels in the DB. Examples: [%s]", missingLabels.size(), examples);
      logger.atInfo().log(errorMessage);
      errors.add(errorMessage);
    }
    SetView<String> unexpectedLabels = Sets.difference(persistedLabels, downloadedLabels);
    if (!unexpectedLabels.isEmpty()) {
      String examples = Joiner.on(',').join(Iterables.limit(unexpectedLabels, nErrorExamples));
      String errorMessage =
          String.format(
              "Found %d unexpected labels in the DB. Examples: [%s]",
              unexpectedLabels.size(), examples);
      logger.atInfo().log(errorMessage);
      errors.add(errorMessage);
    }
    return errors.build();
  }

  ImmutableList<String> checkWronglyReportedUnblockableDomains() {
    ImmutableList.Builder<String> errors = new ImmutableList.Builder<>();
    Optional<UnblockableDomain> lastRead = Optional.empty();
    ImmutableList<UnblockableDomain> batch;
    do {
      batch = Queries.batchReadUnblockableDomains(lastRead, transactionBatchSize);
      ImmutableMap<String, VKey<Domain>> activeDomains =
          ForeignKeyUtils.load(
              Domain.class,
              batch.stream().map(UnblockableDomain::domainName).collect(toImmutableList()),
              clock.nowUtc());
      for (var unblockable : batch) {
        verifyDomainStillUnblockableWithReason(unblockable, activeDomains).ifPresent(errors::add);
      }
      if (!batch.isEmpty()) {
        lastRead = Optional.of(Iterables.getLast(batch));
      }
    } while (batch.size() == transactionBatchSize);
    return errors.build();
  }

  Optional<String> verifyDomainStillUnblockableWithReason(
      UnblockableDomain domain, ImmutableMap<String, VKey<Domain>> activeDomains) {
    DateTime now = clock.nowUtc();
    boolean isRegistered = activeDomains.containsKey(domain.domainName());
    boolean isReserved = isReservedDomain(domain.domainName(), now);
    InternetDomainName domainName = InternetDomainName.from(domain.domainName());
    boolean isInvalid = idnChecker.getAllValidIdns(domainName.parts().get(0)).isEmpty();

    Reason expectedReason =
        isRegistered
            ? Reason.REGISTERED
            : (isReserved ? Reason.RESERVED : (isInvalid ? Reason.INVALID : null));
    if (Objects.equals(expectedReason, domain.reason())) {
      return Optional.empty();
    }
    // Registered name still reported with other reasons: Don't report if registration is recent.
    // Note that staleness is not tolerated if deregistered name is still reported as registered:
    // in this case we do not have the VKey on hand, and it is not worth the effort to find it
    // out.
    if (isRegistered && isStalenessAllowed(activeDomains.get(domain.domainName()))) {
        return Optional.empty();
    }
    return Optional.of(
        String.format(
            "%s: should be %s, found %s",
            domain.domainName(),
            expectedReason != null ? expectedReason.name() : "BLOCKABLE",
            domain.reason()));
  }

  boolean isStalenessAllowed(VKey<Domain> domainVKey) {
    Domain domain = bsaQuery(() -> replicaTm().loadByKey(domainVKey));
    var now = clock.nowUtc();
    return domain.getCreationTime().plus(maxStaleness).isAfter(now);
  }

  /** Returns unique labels across all block lists in the download specified by {@code jobName}. */
  ImmutableSet<String> fetchDownloadedLabels(String jobName) {
    ImmutableSet.Builder<String> labelsBuilder = new ImmutableSet.Builder<>();
    for (BlockListType blockListType : BlockListType.values()) {
      try (Stream<String> lines = gcsClient.readBlockList(jobName, blockListType)) {
        lines
            .skip(1)
            .map(BsaValidateAction::parseBlockListLine)
            .filter(label -> !idnChecker.getAllValidIdns(label).isEmpty())
            .forEach(labelsBuilder::add);
      }
    }
    return labelsBuilder.build();
  }

  ImmutableSet<String> fetchPersistedLabels(int batchSize) {
    ImmutableSet.Builder<String> labelsBuilder = new ImmutableSet.Builder<>();
    ImmutableList<String> batch;
    Optional<String> lastRead = Optional.empty();
    do {
      batch = batchReadBsaLabelText(lastRead, batchSize);
      labelsBuilder.addAll(batch);
      if (!batch.isEmpty()) {
        lastRead = Optional.of(Iterables.getLast(batch));
      }

    } while (batch.size() == batchSize);
    return labelsBuilder.build();
  }

  ImmutableList<String> checkMissingUnblockableDomains() {
    DateTime now = clock.nowUtc();
    ImmutableList.Builder<String> errors = new ImmutableList.Builder<>();
    errors.addAll(checkForMissingReservedUnblockables(now));
    errors.addAll(checkForMissingRegisteredUnblockables(now));
    return errors.build();
  }

  ImmutableList<String> checkForMissingRegisteredUnblockables(DateTime now) {
    ImmutableList.Builder<String> errors = new ImmutableList.Builder<>();
    ImmutableList<Tld> bsaEnabledTlds =
        getTldEntitiesOfType(TldType.REAL).stream()
            .filter(tld -> isEnrolledWithBsa(tld, now))
            .collect(toImmutableList());
    DateTime stalenessThreshold = now.minus(maxStaleness);
    bsaEnabledTlds.stream()
        .map(Tld::getTldStr)
        .map(tld -> bsaQuery(() -> queryMissedRegisteredUnblockables(tld, now)))
        .flatMap(ImmutableList::stream)
        .filter(domainLifeSpan -> domainLifeSpan.creationTime().isBefore(stalenessThreshold))
        .map(DomainLifeSpan::domainName)
        .forEach(
            domainName ->
                errors.add(
                    String.format(
                        "Registered domain %s missing or not recorded as REGISTERED", domainName)));
    return errors.build();
  }

  ImmutableList<String> checkForMissingReservedUnblockables(DateTime now) {
    ImmutableList.Builder<String> errors = new ImmutableList.Builder<>();
    try (Stream<ImmutableList<String>> reservedNames =
        toBatches(
            getAllReservedNames(now).filter(BsaValidateAction::isBlockedByBsa),
            transactionBatchSize)) {
      reservedNames
          .map(this::checkOneBatchReservedDomainsForMissingUnblockables)
          .forEach(errors::addAll);
    }
    return errors.build();
  }

  ImmutableList<String> checkOneBatchReservedDomainsForMissingUnblockables(
      ImmutableList<String> batch) {
    ImmutableSet<String> labels =
        batch.stream()
            .map(InternetDomainName::from)
            .map(d -> d.parts().get(0))
            .collect(toImmutableSet());
    ImmutableMap<String, UnblockableDomain> persistedUnblockables =
        bsaQuery(
            () ->
                queryUnblockableDomainByLabels(labels)
                    .collect(toImmutableMap(UnblockableDomain::domainName, x -> x)));
    ImmutableList.Builder<String> errors = new ImmutableList.Builder<>();
    ImmutableSet<UnblockableDomain.Reason> acceptableReasons =
        ImmutableSet.of(Reason.REGISTERED, Reason.RESERVED);
    for (var domainName : batch) {
      if (!persistedUnblockables.containsKey(domainName)) {
        errors.add(String.format("Missing unblockable domain: %s is reserved.", domainName));
        continue;
      }
      var unblockable = persistedUnblockables.get(domainName);
      if (!acceptableReasons.contains(unblockable.reason())) {
        errors.add(
            String.format(
                "Wrong unblockable reason: %s should be reserved or registered, found %s.",
                domainName, unblockable.reason()));
      }
    }
    return errors.build();
  }

  static boolean isBlockedByBsa(String domainInBsaEnrolledTld) {
    InternetDomainName domainName = InternetDomainName.from(domainInBsaEnrolledTld);
    return isLabelBlocked(domainName.parts().get(0));
  }

  static String parseBlockListLine(String line) {
    int firstComma = line.indexOf(',');
    checkArgument(firstComma > 0, "Invalid block list line: %s", line);
    return line.substring(0, firstComma);
  }
}
