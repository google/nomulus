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

package google.registry.dns;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.dns.DnsModule.PARAM_DNS_WRITER;
import static google.registry.dns.DnsModule.PARAM_DOMAINS;
import static google.registry.dns.DnsModule.PARAM_HOSTS;
import static google.registry.dns.DnsModule.PARAM_LOCK_INDEX;
import static google.registry.dns.DnsModule.PARAM_NUM_PUBLISH_LOCKS;
import static google.registry.dns.DnsModule.PARAM_PUBLISH_TASK_ENQUEUED;
import static google.registry.dns.DnsModule.PARAM_REFRESH_REQUEST_TIME;
import static google.registry.dns.DnsUtils.DNS_PUBLISH_PUSH_QUEUE_NAME;
import static google.registry.dns.DnsUtils.requestDomainDnsRefresh;
import static google.registry.dns.DnsUtils.requestHostDnsRefresh;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_TLD;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static jakarta.servlet.http.HttpServletResponse.SC_ACCEPTED;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InternetDomainName;
import dagger.Lazy;
import google.registry.batch.CloudTasksUtils;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.DnsMetrics.ActionStatus;
import google.registry.dns.DnsMetrics.CommitStatus;
import google.registry.dns.DnsMetrics.PublishStatus;
import google.registry.dns.writer.DnsWriter;
import google.registry.groups.GmailClient;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.tld.Tld;
import google.registry.request.Action;
import google.registry.request.Header;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import google.registry.util.DomainNameUtils;
import google.registry.util.EmailMessage;
import jakarta.inject.Inject;
import jakarta.mail.internet.InternetAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Task that sends domain and host updates to the DNS server. */
@Action(
    service = Action.Service.BACKEND,
    path = PublishDnsUpdatesAction.PATH,
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_ADMIN)
public final class PublishDnsUpdatesAction implements Runnable, Callable<Void> {

  public static final String PATH = "/_dr/task/publishDnsUpdates";
  public static final String LOCK_NAME = "DNS updates";
  public static final String CLOUD_TASKS_RETRY_HEADER = "X-CloudTasks-TaskRetryCount";
  public static final int RETRIES_BEFORE_PERMANENT_FAILURE = 20;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final DnsWriterProxy dnsWriterProxy;
  private final DnsMetrics dnsMetrics;
  private final Duration timeout;
  private final int retryCount;

  /**
   * The DNS writer to use for this batch.
   *
   * <p>This comes from the fanout in {@link ReadDnsRefreshRequestsAction} which dispatches each
   * batch to be published by each DNS writer on the TLD. So this field contains the value of one of
   * the DNS writers configured in {@link Tld#getDnsWriters()}, as of the time the batch was written
   * out (and not necessarily currently).
   */
  private final String dnsWriter;

  private final DateTime enqueuedTime;
  private final DateTime itemsCreateTime;
  private final int lockIndex;
  private final int numPublishLocks;
  private final Set<String> domains;
  private final Set<String> hosts;
  private final String tld;
  private final LockHandler lockHandler;
  private final Clock clock;
  private final CloudTasksUtils cloudTasksUtils;
  private final Response response;
  private final GmailClient gmailClient;
  private final String dnsUpdateFailEmailSubjectText;
  private final String dnsUpdateFailEmailBodyText;
  private final String dnsUpdateFailRegistryName;
  private final Lazy<InternetAddress> registrySupportEmail;
  private final Lazy<InternetAddress> registryCcEmail;

  @Inject
  public PublishDnsUpdatesAction(
      @Parameter(PARAM_DNS_WRITER) String dnsWriter,
      @Parameter(PARAM_PUBLISH_TASK_ENQUEUED) DateTime enqueuedTime,
      @Parameter(PARAM_REFRESH_REQUEST_TIME) DateTime itemsCreateTime,
      @Parameter(PARAM_LOCK_INDEX) int lockIndex,
      @Parameter(PARAM_NUM_PUBLISH_LOCKS) int numPublishLocks,
      @Parameter(PARAM_DOMAINS) Set<String> domains,
      @Parameter(PARAM_HOSTS) Set<String> hosts,
      @Parameter(PARAM_TLD) String tld,
      @Config("publishDnsUpdatesLockDuration") Duration timeout,
      @Config("dnsUpdateFailEmailSubjectText") String dnsUpdateFailEmailSubjectText,
      @Config("dnsUpdateFailEmailBodyText") String dnsUpdateFailEmailBodyText,
      @Config("dnsUpdateFailRegistryName") String dnsUpdateFailRegistryName,
      @Config("registrySupportEmail") Lazy<InternetAddress> registrySupportEmail,
      @Config("registryCcEmail") Lazy<InternetAddress> registryCcEmail,
      @Header(CLOUD_TASKS_RETRY_HEADER) int retryCount,
      DnsWriterProxy dnsWriterProxy,
      DnsMetrics dnsMetrics,
      LockHandler lockHandler,
      Clock clock,
      CloudTasksUtils cloudTasksUtils,
      GmailClient gmailClient,
      Response response) {
    this.dnsWriterProxy = dnsWriterProxy;
    this.dnsMetrics = dnsMetrics;
    this.timeout = timeout;
    this.gmailClient = gmailClient;
    this.retryCount = retryCount;
    this.dnsWriter = dnsWriter;
    this.enqueuedTime = enqueuedTime;
    this.itemsCreateTime = itemsCreateTime;
    this.lockIndex = lockIndex;
    this.numPublishLocks = numPublishLocks;
    this.domains = domains;
    this.hosts = hosts;
    this.tld = tld;
    this.lockHandler = lockHandler;
    this.clock = clock;
    this.cloudTasksUtils = cloudTasksUtils;
    this.response = response;
    this.dnsUpdateFailEmailBodyText = dnsUpdateFailEmailBodyText;
    this.dnsUpdateFailEmailSubjectText = dnsUpdateFailEmailSubjectText;
    this.dnsUpdateFailRegistryName = dnsUpdateFailRegistryName;
    this.registrySupportEmail = registrySupportEmail;
    this.registryCcEmail = registryCcEmail;
  }

  private void recordActionResult(ActionStatus status) {
    DateTime now = clock.nowUtc();

    dnsMetrics.recordActionResult(
        tld,
        dnsWriter,
        status,
        nullToEmpty(domains).size() + nullToEmpty(hosts).size(),
        new Duration(itemsCreateTime, now),
        new Duration(enqueuedTime, now));
    logger.atInfo().log(
        "publishDnsWriter latency statistics: TLD: %s, dnsWriter: %s, actionStatus: %s, "
            + "numItems: %d, timeSinceCreation: %s, timeInQueue: %s.",
        tld,
        dnsWriter,
        status,
        nullToEmpty(domains).size() + nullToEmpty(hosts).size(),
        new Duration(itemsCreateTime, now),
        new Duration(enqueuedTime, now));
  }

  /** Runs the task. */
  @Override
  public void run() {
    if (!validLockParams()) {
      recordActionResult(ActionStatus.BAD_LOCK_INDEX);
      requeueBatch();
      return;
    }
    // If executeWithLocks fails to get the lock, it does not throw an exception, simply returns
    // false. We need to make sure to take note of this error; otherwise, a failed lock might
    // result in the update task being dequeued and dropped. A message will already have been
    // logged to indicate the problem.
    if (!lockHandler.executeWithLocks(
        this,
        tld,
        timeout,
        String.format("%s-lock %d of %d", LOCK_NAME, lockIndex, numPublishLocks))) {
      recordActionResult(ActionStatus.LOCK_FAILURE);
      throw new ServiceUnavailableException("Lock failure");
    }
  }

  /** Runs the task, with the lock. */
  @Override
  public Void call() {
    try {
      processBatch();
    } catch (Throwable e) {
      // Retry the batch 3 times
      if (retryCount < 3) {
        throw e;
      }
      // After 3 retries, split the batch
      if (domains.size() > 1 || hosts.size() > 1) {
        // split batch and requeue
        splitBatch();
      } else if (domains.size() == 1 && hosts.size() == 1) {
        // Enqueue the single domain and single host separately
        enqueue(ImmutableList.copyOf(domains), ImmutableList.of());
        enqueue(ImmutableList.of(), ImmutableList.copyOf(hosts));
      } else if (retryCount < RETRIES_BEFORE_PERMANENT_FAILURE) {
        // If the batch only contains 1 name, allow it more retries
        throw e;
      } else {
        // By the time we get here there's either single domain or a single host
        domains.stream()
            .findFirst()
            .ifPresent(
                dn -> {
                  Optional<Domain> domain =
                      ForeignKeyUtils.loadResource(Domain.class, dn, clock.nowUtc());
                  if (domain.isPresent()) {
                    notifyWithEmailAboutDnsUpdateFailure(
                        domain.get().getCurrentSponsorRegistrarId(), dn, false);
                  } else {
                    logger.atSevere().log(String.format("Domain entity for %s not found", dn));
                  }
                });

        hosts.stream()
            .findFirst()
            .ifPresent(
                hn -> {
                  Optional<Host> host =
                      ForeignKeyUtils.loadResource(Host.class, hn, clock.nowUtc());
                  if (host.isPresent()) {
                    notifyWithEmailAboutDnsUpdateFailure(
                        host.get().getPersistedCurrentSponsorRegistrarId(), hn, true);
                  } else {
                    logger.atSevere().log(String.format("Host entity for %s not found", hn));
                  }
                });
      }

      recordActionResult(ActionStatus.MAX_RETRIES_EXCEEDED);
      response.setStatus(SC_ACCEPTED);
      logger.atSevere().withCause(e).log("Terminated task after too many retries");
    }
    return null;
  }

  private static InternetAddress emailToInternetAddress(String email) {
    try {
      return new InternetAddress(email, true);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log(
          String.format(
              "Could not parse email contact %s to send DNS failure notification", email));
      return null;
    }
  }

  /** Sends an email to partners regarding a failure during DNS update */
  private void notifyWithEmailAboutDnsUpdateFailure(
      String registrarId, String hostOrDomainName, Boolean isHost) {
    Optional<Registrar> registrar = Registrar.loadByRegistrarIdCached(registrarId);

    if (registrar.isPresent()) {
      String body =
          String.format(
              dnsUpdateFailEmailBodyText,
              registrar.get().getRegistrarName(),
              hostOrDomainName,
              isHost ? "host" : "domain",
              registrySupportEmail.get().getAddress(),
              dnsUpdateFailRegistryName);

      ImmutableList<InternetAddress> recipients =
          registrar.get().getContacts().stream()
              .filter(c -> c.getTypes().contains(RegistrarPoc.Type.ADMIN))
              .map(RegistrarPoc::getEmailAddress)
              .map(PublishDnsUpdatesAction::emailToInternetAddress)
              .collect(toImmutableList());

      gmailClient.sendEmail(
          EmailMessage.newBuilder()
              .setBody(body)
              .setSubject(dnsUpdateFailEmailSubjectText)
              .setRecipients(recipients)
              .addBcc(registryCcEmail.get())
              .build());

    } else {
      logger.atSevere().log(String.format("Could not find registrar %s", registrarId));
    }
  }

  /** Splits the domains and hosts in a batch into smaller batches and adds them to the queue. */
  private void splitBatch() {
    ImmutableList<String> domainList = ImmutableList.copyOf(domains);
    ImmutableList<String> hostList = ImmutableList.copyOf(hosts);

    enqueue(domainList.subList(0, domains.size() / 2), hostList.subList(0, hosts.size() / 2));
    enqueue(
        domainList.subList(domains.size() / 2, domains.size()),
        hostList.subList(hosts.size() / 2, hosts.size()));
  }

  private void enqueue(ImmutableList<String> domains, ImmutableList<String> hosts) {
    cloudTasksUtils.enqueue(
        DNS_PUBLISH_PUSH_QUEUE_NAME,
        cloudTasksUtils.createTask(
            this.getClass(),
            POST,
            ImmutableMultimap.<String, String>builder()
                .put(PARAM_TLD, tld)
                .put(PARAM_DNS_WRITER, dnsWriter)
                .put(PARAM_LOCK_INDEX, Integer.toString(lockIndex))
                .put(PARAM_NUM_PUBLISH_LOCKS, Integer.toString(numPublishLocks))
                .put(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
                .put(PARAM_REFRESH_REQUEST_TIME, itemsCreateTime.toString())
                .put(PARAM_DOMAINS, Joiner.on(",").join(domains))
                .put(PARAM_HOSTS, Joiner.on(",").join(hosts))
                .build()));
  }

  /** Adds all the domains and hosts in the batch back to the queue to be processed later. */
  private void requeueBatch() {
    logger.atInfo().log("Requeueing batch for retry.");
    tm().transact(
            () -> {
              requestDomainDnsRefresh(ImmutableSet.copyOf(nullToEmpty(domains)));
              requestHostDnsRefresh(ImmutableSet.copyOf(nullToEmpty(hosts)));
            });
  }

  /** Returns if the lock parameters are valid for this action. */
  private boolean validLockParams() {
    // LockIndex should always be within [1, numPublishLocks]
    if (lockIndex > numPublishLocks || lockIndex <= 0) {
      logger.atSevere().log(
          "Lock index should be within [1,%d], got %d instead.", numPublishLocks, lockIndex);
      return false;
    }
    // Check if the Registry object's num locks has changed since this task was batched
    int tldNumPublishLocks = Tld.get(tld).getNumDnsPublishLocks();
    if (tldNumPublishLocks != numPublishLocks) {
      logger.atWarning().log(
          "Registry numDnsPublishLocks %d out of sync with parameter %d.",
          tldNumPublishLocks, numPublishLocks);
      return false;
    }
    return true;
  }

  /** Steps through the domain and host refreshes contained in the parameters and processes them. */
  private void processBatch() {
    DateTime timeAtStart = clock.nowUtc();

    DnsWriter writer = dnsWriterProxy.getByClassNameForTld(dnsWriter, tld);

    if (writer == null) {
      logger.atWarning().log("Couldn't get writer %s for TLD %s.", dnsWriter, tld);
      recordActionResult(ActionStatus.BAD_WRITER);
      requeueBatch();
      return;
    }

    int domainsPublished = 0;
    int domainsRejected = 0;
    for (String domain : nullToEmpty(domains)) {
      if (!DomainNameUtils.isUnder(InternetDomainName.from(domain), InternetDomainName.from(tld))) {
        logger.atSevere().log("%s: skipping domain %s not under TLD.", tld, domain);
        domainsRejected += 1;
      } else {
        writer.publishDomain(domain);
        logger.atInfo().log("%s: published domain %s.", tld, domain);
        domainsPublished += 1;
      }
    }
    dnsMetrics.incrementPublishDomainRequests(tld, domainsPublished, PublishStatus.ACCEPTED);
    dnsMetrics.incrementPublishDomainRequests(tld, domainsRejected, PublishStatus.REJECTED);

    int hostsPublished = 0;
    int hostsRejected = 0;
    for (String host : nullToEmpty(hosts)) {
      if (!DomainNameUtils.isUnder(InternetDomainName.from(host), InternetDomainName.from(tld))) {
        logger.atSevere().log("%s: skipping host %s not under TLD.", tld, host);
        hostsRejected += 1;
      } else {
        writer.publishHost(host);
        logger.atInfo().log("%s: published host %s.", tld, host);
        hostsPublished += 1;
      }
    }
    dnsMetrics.incrementPublishHostRequests(tld, hostsPublished, PublishStatus.ACCEPTED);
    dnsMetrics.incrementPublishHostRequests(tld, hostsRejected, PublishStatus.REJECTED);

    // If we got here it means we managed to stage the entire batch without any errors.
    // Next we will commit the batch.
    CommitStatus commitStatus = CommitStatus.FAILURE;
    ActionStatus actionStatus = ActionStatus.COMMIT_FAILURE;
    try {
      writer.commit();
      // No error was thrown
      commitStatus = CommitStatus.SUCCESS;
      actionStatus = ActionStatus.SUCCESS;
    } finally {
      recordActionResult(actionStatus);
      Duration duration = new Duration(timeAtStart, clock.nowUtc());
      dnsMetrics.recordCommit(
          tld, dnsWriter, commitStatus, duration, domainsPublished, hostsPublished);
      logger.atInfo().log(
          "writer.commit() statistics: TLD: %s, dnsWriter: %s, commitStatus: %s, duration: %s, "
              + "domainsPublished: %d, domainsRejected: %d, hostsPublished: %d, hostsRejected: %d.",
          tld,
          dnsWriter,
          commitStatus,
          duration,
          domainsPublished,
          domainsRejected,
          hostsPublished,
          hostsRejected);
    }
  }
}
