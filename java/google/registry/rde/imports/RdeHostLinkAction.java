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

import static google.registry.flows.host.HostFlowUtils.lookupSuperordinateDomain;
import static google.registry.mapreduce.MapreduceRunner.PARAM_MAP_SHARDS;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.PipelineUtils.createJobPath;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import google.registry.config.RegistryConfig.Config;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.EppResource;
import google.registry.model.domain.DomainResource;
import google.registry.model.host.HostResource;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.util.FormattingLogger;
import google.registry.xjc.JaxbFragment;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * A mapreduce that links hosts from an escrow file to their superordinate domains.
 *
 * <p>This mapreduce is run as the last step of the process of importing escrow files. For each host
 * in the escrow file, the corresponding {@link HostResource} record in Datastore is linked to its
 * superordinate {@link DomainResource} only if it is an in-zone host. This is necessary because all
 * hosts must exist before domains can be imported, due to references in host objects, and domains
 * must exist before hosts can be linked to their superordinate domains.
 *
 * <p>Specify the escrow file to import with the "path" parameter.
 */
@Action(path = "/_dr/task/linkRdeHosts")
public class RdeHostLinkAction implements Runnable {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  private final MapreduceRunner mrRunner;
  private final Response response;
  private final String importBucketName;
  private final String importFileName;
  private final Optional<Integer> mapShards;

  @Inject
  public RdeHostLinkAction(
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
        .setJobName("Link hosts from escrow file")
        .setModuleName("backend")
        .runMapOnly(
            new RdeHostPostImportMapper(),
            ImmutableList.of(new RdeHostInput(mapShards, importBucketName, importFileName)))));
  }

  /** Mapper to link hosts from an escrow file to their superordinate domains. */
  public static class RdeHostPostImportMapper
      extends Mapper<JaxbFragment<XjcRdeHostElement>, Void, Void> {

    private static final long serialVersionUID = -2898753709127134419L;

    @Override
    public void map(JaxbFragment<XjcRdeHostElement> fragment) {
      // Record number of attempted map operations
      getContext().incrementCounter("post-import hosts read");
      final XjcRdeHost xjcHost = fragment.getInstance().getValue();
      logger.infofmt("Attempting to link superordinate domain for host %s", xjcHost.getName());
      try {
        final InternetDomainName hostName = InternetDomainName.from(xjcHost.getName());

        HostLinkResult hostLinkResult = ofy().transact(new Work<HostLinkResult>() {
          @Override
          public HostLinkResult run() {
            Optional<DomainResource> superordinateDomain =
                lookupSuperordinateDomain(hostName, ofy().getTransactionTime());
            // if suporordinateDomain is absent, this is an out of zone host and can't be linked.
            // absent is only returned for out of zone hosts, and an exception is thrown for in
            // zone hosts with no superordinate domain.
            if (!superordinateDomain.isPresent()) {
              return HostLinkResult.HOST_OUT_OF_ZONE;
            }
            Key<DomainResource> superordinateDomainKey = Key.create(superordinateDomain.get());
            // link host to superordinate domain and set time of last superordinate change to
            // the time of the import
            HostResource host =
                ofy().load().now(Key.create(HostResource.class, xjcHost.getRoid()));
            if (host == null) {
              return HostLinkResult.HOST_NOT_FOUND;
            }
            // link domain to subordinate host
            ofy().save().<EppResource>entities(
                host.asBuilder().setSuperordinateDomain(superordinateDomainKey)
                    .setLastSuperordinateChange(ofy().getTransactionTime())
                    .build(),
                superordinateDomain.get().asBuilder()
                    .addSubordinateHost(host.getFullyQualifiedHostName()).build());
            return HostLinkResult.HOST_LINKED;
          }
        });
        // increment counter and log appropriately based on result of transaction
        switch (hostLinkResult) {
          case HOST_LINKED:
            logger.infofmt(
                "Successfully linked host %s to superordinate domain", xjcHost.getName());
            // Record number of hosts successfully linked
            getContext().incrementCounter("post-import hosts linked");
            break;
          case HOST_NOT_FOUND:
            getContext().incrementCounter("hosts not found");
            logger.severefmt(
                "Host with name %s and repoid %s not found",
                xjcHost.getName(),
                xjcHost.getRoid());
            break;
          case HOST_OUT_OF_ZONE:
            getContext().incrementCounter("post-import hosts out of zone");
            logger.infofmt("Host %s is out of zone", xjcHost.getName());
            break;
        }
      } catch (RuntimeException e) {
        // Record the number of hosts with unexpected errors
        getContext().incrementCounter("post-import host errors");
        logger.severefmt(e, "Error linking host %s; xml=%s", xjcHost.getName(), xjcHost);
      }
    }
  }

  private static enum HostLinkResult {
    HOST_NOT_FOUND,
    HOST_OUT_OF_ZONE,
    HOST_LINKED;
  }

  private static class HostLinkException extends RuntimeException {
    HostLinkException(String hostname, String xml, Throwable cause) {
      super(String.format("Error linking host %s; xml=%s", hostname, xml), cause);
    }
  }
}
