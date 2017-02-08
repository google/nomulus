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
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.host.HostResource;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.util.FormattingLogger;
import google.registry.util.SystemClock;
import google.registry.xjc.JaxbFragment;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;
import javax.inject.Inject;

/**
 * A mapreduce that imports hosts from an escrow file.
 *
 * <p>Specify the escrow file to import with the "path" parameter.
 */
@Action(path = "/_dr/task/importRdeHosts")
public class RdeHostImportAction implements Runnable {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();
  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  private final MapreduceRunner mrRunner;
  private final Response response;
  private final String importBucketName;
  private final String importFileName;
  private final Optional<Integer> mapShards;

  @Inject
  public RdeHostImportAction(
      MapreduceRunner mrRunner,
      Response response,
      @Config("rdeImportBucket") String importBucketName,
      @Parameter("path") String importFileName,
      @Parameter(PARAM_MAP_SHARDS) Optional<Integer> mapShards) {
    this.mrRunner = mrRunner;
    this.response = response;
    this.importBucketName = importBucketName;
    this.importFileName = importFileName;
    this.mapShards = mapShards;
  }

  @Override
  public void run() {
    response.sendJavaScriptRedirect(createJobPath(mrRunner
        .setJobName("Import hosts from escrow file")
        .setModuleName("backend")
        .runMapOnly(
            new RdeHostImportMapper(importBucketName),
            ImmutableList.of(new RdeHostInput(mapShards, importBucketName, importFileName)))));
  }

  /** Mapper to import hosts from an escrow file. */
  public static class RdeHostImportMapper
      extends Mapper<JaxbFragment<XjcRdeHostElement>, Void, Void> {

    private static final long serialVersionUID = -2898753709127134419L;

    private final String importBucketName;
    private transient RdeImportUtils importUtils;

    public RdeHostImportMapper(String importBucketName) {
      this.importBucketName = importBucketName;
    }

    private RdeImportUtils getImportUtils() {
      if (importUtils == null) {
        importUtils = createRdeImportUtils();
      }
      return importUtils;
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
    public void map(JaxbFragment<XjcRdeHostElement> fragment) {
      final XjcRdeHost xjcHost = fragment.getInstance().getValue();
      try {
        // Record number of attempted map operations
        getContext().incrementCounter("host imports attempted");
        logger.infofmt("Saving host %s", xjcHost.getName());
        ofy().transact(new VoidWork() {

          @Override
          public void vrun() {
            HostResource host = XjcToHostResourceConverter.convert(xjcHost);
            getImportUtils().importHost(host);
          }
        });
        // Record number of hosts imported
        getContext().incrementCounter("hosts saved");
        logger.infofmt("Host %s was imported successfully", xjcHost.getName());
      } catch (ResourceExistsException e) {
        // Record the number of hosts already in the registry
        getContext().incrementCounter("existing hosts skipped");
        logger.infofmt("Host %s already exists", xjcHost.getName());
      } catch (Exception e) {
        // Record the number of hosts with unexpected errors
        getContext().incrementCounter("host import errors");
        logger.severefmt(e, "Error processing host %s; xml=%s", xjcHost.getName(), xjcHost);
      }
    }
  }
}
