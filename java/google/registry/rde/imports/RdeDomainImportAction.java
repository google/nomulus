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

import static google.registry.mapreduce.MapreduceRunner.PARAM_MAP_SHARDS;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.rde.imports.RdeImportsModule.PATH;
import static google.registry.util.PipelineUtils.createJobPath;

import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.VoidWork;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.dns.DnsQueue;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.domain.DomainResource;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.util.FormattingLogger;
import google.registry.util.SystemClock;
import google.registry.xjc.JaxbFragment;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdedomain.XjcRdeDomainElement;
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
        logger.infofmt("Converting xml for domain %s", xjcDomain.getName());
        // Record number of attempted map operations
        getContext().incrementCounter("domain imports attempted");
        logger.infofmt("Saving domain %s", xjcDomain.getName());
        ofy().transact(new VoidWork() {
          @Override
          public void vrun() {
            DomainResource domain =
                XjcToDomainResourceConverter.convertDomain(xjcDomain);
            getImportUtils().importDomain(domain);
            getDnsQueue().addDomainRefreshTask(domain.getFullyQualifiedDomainName());
          }
        });
        // Record the number of domains imported
        getContext().incrementCounter("domains saved");
        logger.infofmt("Domain %s was imported successfully", xjcDomain.getName());
      } catch (ResourceExistsException e) {
        // Record the number of domains already in the registry
        getContext().incrementCounter("existing domains skipped");
        logger.infofmt("Domain %s already exists", xjcDomain.getName());
      } catch (Exception e) {
        getContext().incrementCounter("domain import errors");
        logger.severefmt(e, "Error processing domain %s; xml=%s", xjcDomain.getName(), xjcDomain);
      }
    }
  }
}
