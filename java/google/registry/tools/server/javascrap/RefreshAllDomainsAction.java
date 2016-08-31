// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.tools.server.javascrap;

import static google.registry.mapreduce.inputs.EppResourceInputs.createEntityInput;
import static google.registry.util.PipelineUtils.createJobPath;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.collect.ImmutableList;
import google.registry.dns.DnsQueue;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.EppResourceUtils;
import google.registry.model.domain.DomainResource;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.util.FormattingLogger;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/** A mapreduce that enqueues publish tasks on all active domains. */
@Action(path = "/_dr/task/refreshAllDomains")
public class RefreshAllDomainsAction implements Runnable {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  private static DnsQueue dnsQueue = DnsQueue.create();

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject RefreshAllDomainsAction() {}

  @Override
  public void run() {
    response.sendJavaScriptRedirect(
        createJobPath(
            mrRunner
                .setJobName("Refresh all domains")
                .setModuleName("tools")
                .setDefaultMapShards(10)
                .runMapOnly(
                    new RefreshAllDomainsActionMapper(),
                    ImmutableList.of(createEntityInput(DomainResource.class)))));
  }

  /** Mapper to refresh all active domain resources. */
  public static class RefreshAllDomainsActionMapper extends Mapper<DomainResource, Void, Void> {

    private static final long serialVersionUID = 1356876487351666133L;

    @Override
    public void map(final DomainResource domain) {
      String domainName = domain.getFullyQualifiedDomainName();
      if (EppResourceUtils.isActive(domain, DateTime.now(DateTimeZone.UTC))) {
        try {
          dnsQueue.addDomainRefreshTask(domainName);
          getContext().incrementCounter("active domains refreshed");
        } catch (Throwable t) {
          logger.severefmt(t, "Error while refreshing DNS for domain %s", domainName);
          getContext().incrementCounter("active domains errored");
        }
      } else {
        getContext().incrementCounter("inactive domains skipped");
      }
    }
  }
}
