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

package google.registry.rde.imports;

import static google.registry.flows.domain.DomainTransferUtils.createLosingTransferPollMessage;
import static google.registry.flows.domain.DomainTransferUtils.createPendingTransferData;
import static google.registry.flows.domain.DomainTransferUtils.createTransferServerApproveEntities;
import static google.registry.mapreduce.MapreduceRunner.PARAM_MAP_SHARDS;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.rde.imports.RdeImportUtils.createAutoRenewBillingEventForDomainImport;
import static google.registry.rde.imports.RdeImportUtils.createAutoRenewPollMessageForDomainImport;
import static google.registry.rde.imports.RdeImportUtils.createHistoryEntryForDomainImport;
import static google.registry.rde.imports.RdeImportsModule.PATH;
import static google.registry.util.PipelineUtils.createJobPath;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.VoidWork;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.dns.DnsQueue;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.model.transfer.TransferStatus;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.util.FormattingLogger;
import google.registry.util.SystemClock;
import google.registry.xjc.JaxbFragment;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdedomain.XjcRdeDomainElement;
import org.joda.money.Money;
import javax.inject.Inject;

/**
 * A mapreduce that imports domains from an escrow file.
 *
 * <p>Specify the escrow file to import with the "path" parameter.
 */
@Action(path = "/_dr/task/importRdeDomains")
public class RdeDomainImportAction implements Runnable {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();
  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  protected final MapreduceRunner mrRunner;
  protected final Response response;
  protected final String importBucketName;
  protected final String importFileName;
  protected final Optional<Integer> mapShards;

  @Inject
  public RdeDomainImportAction(
      MapreduceRunner mrRunner,
      Response response,
      @Config("rdeImportBucket") String importBucketName,
      @Parameter(PATH) String importFileName,
      @Parameter(PARAM_MAP_SHARDS) Optional<Integer> mapShards) {
    this.mrRunner = mrRunner;
    this.response = response;
    this.importBucketName = importBucketName;
    this.importFileName = importFileName;
    this.mapShards = mapShards;
  }

  @Override
  public void run() {
    logger.infofmt(
        "Launching domains import mapreduce: bucket=%s, filename=%s",
        this.importBucketName,
        this.importFileName);
    response.sendJavaScriptRedirect(createJobPath(mrRunner
        .setJobName("Import domains from escrow file")
        .setModuleName("backend")
        .runMapOnly(
            createMapper(),
            ImmutableList.of(createInput()))));
  }

  /**
   * Creates a new {@link RdeDomainInput}
   */
  private RdeDomainInput createInput() {
    return new RdeDomainInput(mapShards, importBucketName, importFileName);
  }

  /**
   * Creates a new {@link RdeDomainImportMapper}
   */
  private RdeDomainImportMapper createMapper() {
    return new RdeDomainImportMapper(importBucketName);
  }

  /** Mapper to import domains from an escrow file. */
  public static class RdeDomainImportMapper
      extends Mapper<JaxbFragment<XjcRdeDomainElement>, Void, Void> {

    private static final long serialVersionUID = -7645091075256589374L;

    private final String importBucketName;
    private transient RdeImportUtils importUtils;
    private transient DnsQueue dnsQueue;

    public RdeDomainImportMapper(String importBucketName) {
      this.importBucketName = importBucketName;
    }

    private RdeImportUtils getImportUtils() {
      if (importUtils == null) {
        importUtils = createRdeImportUtils();
      }
      return importUtils;
    }

    private DnsQueue getDnsQueue() {
      if (dnsQueue == null) {
        dnsQueue = DnsQueue.create();
      }
      return dnsQueue;
    }

    /**
     * Creates a new instance of RdeImportUtils.
     */
    private RdeImportUtils createRdeImportUtils() {
      return new RdeImportUtils(
          ofy(),
          new SystemClock(),
          importBucketName,
          new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize()));
    }

    @Override
    public void map(JaxbFragment<XjcRdeDomainElement> fragment) {
      final XjcRdeDomain xjcDomain = fragment.getInstance().getValue();
      try {
        // Record number of attempted map operations
        getContext().incrementCounter("domain imports attempted");

        logger.infofmt("Saving domain %s", xjcDomain.getName());
        ofy().transact(new VoidWork() {
          @Override
          public void vrun() {
            HistoryEntry historyEntry = createHistoryEntryForDomainImport(xjcDomain);
            BillingEvent.Recurring autorenewBillingEvent =
                createAutoRenewBillingEventForDomainImport(xjcDomain, historyEntry);
            PollMessage.Autorenew autorenewPollMessage =
                createAutoRenewPollMessageForDomainImport(xjcDomain, historyEntry);
            DomainResource domain = XjcToDomainResourceConverter.convertDomain(
                xjcDomain, autorenewBillingEvent, autorenewPollMessage);
            getDnsQueue().addDomainRefreshTask(domain.getFullyQualifiedDomainName());
            // Keep a list of "extra objects" that need to be saved along with the domain
            // and add to it if necessary.
            ImmutableSet<Object> extraEntitiesToSave =
                getImportUtils().getIndexesForEppResource(domain, "domain");
            // Create speculative server approval entities for pending transfers
            if (domain.getTransferData().getTransferStatus() == TransferStatus.PENDING) {
              TransferData transferData = domain.getTransferData();
              checkArgumentNotNull(transferData,
                  "Domain %s is in pending transfer but has no transfer data",
                  domain.getFullyQualifiedDomainName());
              Money transferCost = getDomainRenewCost(
                  domain.getFullyQualifiedDomainName(),
                  transferData.getPendingTransferExpirationTime(),
                  transferData.getExtendedRegistrationYears());
              // Create speculative entities in anticipation of an automatic server approval.
              ImmutableSet<TransferServerApproveEntity> serverApproveEntities =
                  createTransferServerApproveEntities(
                      transferData.getPendingTransferExpirationTime(),
                      domain.getRegistrationExpirationTime()
                          .plusYears(transferData.getExtendedRegistrationYears()),
                      historyEntry,
                      domain,
                      historyEntry.getTrid(),
                      transferData.getGainingClientId(),
                      transferCost,
                      transferData.getExtendedRegistrationYears(),
                      transferData.getTransferRequestTime());
              transferData =
                  createPendingTransferData(transferData.asBuilder(), serverApproveEntities);
              // Create a poll message to notify the losing registrar that a transfer was requested.
              PollMessage requestPollMessage = createLosingTransferPollMessage(domain.getRepoId(),
                  transferData, transferData.getPendingTransferExpirationTime(), historyEntry)
                      .asBuilder().setEventTime(transferData.getTransferRequestTime()).build();
              domain = domain.asBuilder().setTransferData(transferData).build();
              autorenewBillingEvent = autorenewBillingEvent.asBuilder()
                  .setRecurrenceEndTime(transferData.getPendingTransferExpirationTime()).build();
              autorenewPollMessage = autorenewPollMessage.asBuilder()
                  .setAutorenewEndTime(transferData.getPendingTransferExpirationTime()).build();
              extraEntitiesToSave = new ImmutableSet.Builder<>()
                  .add(requestPollMessage)
                  .addAll(extraEntitiesToSave)
                  .addAll(serverApproveEntities).build();
            } // End pending transfer check
            ofy().save()
              .entities(new ImmutableSet.Builder<>()
                .add(domain, historyEntry, autorenewBillingEvent, autorenewPollMessage)
                .addAll(extraEntitiesToSave)
                .build())
            .now();
          }
        });
        // Record the number of domains imported
        getContext().incrementCounter("domains saved");
        logger.infofmt("Domain %s was imported successfully", xjcDomain.getName());
      } catch (ResourceExistsException e) {
        // Record the number of domains already in the registry
        getContext().incrementCounter("domains skipped");
        logger.infofmt("Domain %s already exists", xjcDomain.getName());
      } catch (Exception e) {
        getContext().incrementCounter("domain import errors");
        throw new DomainImportException(xjcDomain.getName(), xjcDomain.toString(), e);
      }
    }

    private void setupPendingTransfer(final HistoryEntry historyEntry,
        DomainResource domain,
        BillingEvent.Recurring autorenewBillingEvent,
        PollMessage.Autorenew autorenewPollMessage) {

    }
  }

  private static class DomainImportException extends RuntimeException {
    DomainImportException(String domainName, String xml, Throwable cause) {
      super(String.format("Error processing domain %s; xml=%s", domainName, xml), cause);
    }
  }
}
