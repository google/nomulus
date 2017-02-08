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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.mapreduce.MapreduceRunner.PARAM_MAP_SHARDS;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.Registries.findTldForName;
import static google.registry.util.PipelineUtils.createJobPath;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.InternetDomainName;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.VoidWork;
import google.registry.config.RegistryConfig.Config;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.domain.DomainResource;
import google.registry.model.host.HostResource;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.util.FormattingLogger;
import google.registry.xjc.JaxbFragment;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;
import org.joda.time.DateTime;

import javax.inject.Inject;

/**
 * A mapreduce that links hosts from an escrow file to their superordinate domains.
 *
 * This mapreduce is run as the last step of the process of importing escrow files. For each host in
 * the escrow file, the corresponding {@link HostResource} record in the datastore is linked to its
 * superordinate {@link DomainResource} only if it is an in-zone host. This is necessary because
 * all hosts must exist before domains can be imported, due to references in host objects, and
 * domains must exist before hosts can be linked to their superordinate domains.
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
        InternetDomainName hostName = InternetDomainName.from(xjcHost.getName());
        Optional<DomainResource> superordinateDomain =
            lookupSuperordinateDomain(hostName, DateTime.now());
        // if suporordinateDomain is null, this is an out of zone host and can't be linked
        if (!superordinateDomain.isPresent()) {
          getContext().incrementCounter("post-import hosts out of zone");
          logger.infofmt("Host %s is out of zone", xjcHost.getName());
          return;
        }
        // at this point, the host is definitely in zone and should be linked
        getContext().incrementCounter("post-import hosts in zone");
        final Key<DomainResource> superordinateDomainKey = Key.create(superordinateDomain.get());
        ofy().transact(new VoidWork() {
          @Override
          public void vrun() {
            HostResource host =
                ofy().load().now(Key.create(HostResource.class, xjcHost.getRoid()));
            ofy().save()
                .entity(host.asBuilder().setSuperordinateDomain(superordinateDomainKey).build());
          }
        });
        logger.infofmt(
            "Successfully linked host %s to superordinate domain %s",
            xjcHost.getName(),
            superordinateDomain.get().getFullyQualifiedDomainName());
        // Record number of hosts successfully linked
        getContext().incrementCounter("post-import hosts linked");
      } catch (Exception e) {
        // Record the number of hosts with unexpected errors
        getContext().incrementCounter("post-import host errors");
        throw new HostLinkException(xjcHost.getName(), xjcHost.toString(), e);
      }
    }

    /**
     * Return the {@link DomainResource} this host is subordinate to, or absent for out of zone hosts.
     *
     * @throws {@link IllegalStateException} for hosts without superordinate domains
     */
    private static Optional<DomainResource> lookupSuperordinateDomain(InternetDomainName hostName,
        DateTime now) {
      Optional<InternetDomainName> tld = findTldForName(hostName);
      // out of zone hosts cannot be linked
      if (!tld.isPresent()) {
        return Optional.absent();
      }
      // This is a subordinate host
      String domainName = Joiner.on('.').join(Iterables.skip(
          hostName.parts(), hostName.parts().size() - (tld.get().parts().size() + 1)));
      DomainResource superordinateDomain = loadByForeignKey(DomainResource.class, domainName, now);
      // Hosts can't be linked if domains import hasn't been run
      checkState(superordinateDomain != null, "Superordinate domain does not exist: %s", domainName);
      return Optional.of(superordinateDomain);
    }
  }

  private static class HostLinkException extends RuntimeException {
    HostLinkException(String hostname, String xml, Throwable cause) {
      super(String.format("Error linking host %s; xml=%s", hostname, xml), cause);
    }
  }
}
