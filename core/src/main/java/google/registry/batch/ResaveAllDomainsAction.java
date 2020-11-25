// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import javax.inject.Inject;

/**
 * A mapreduce that re-saves all Domain entities so all {@link GracePeriod#gracePeriodId}.
 *
 * <p>We added the {@link GracePeriod#gracePeriodId} to help migrate Domain entity to Cloud SQL. As
 * it is a newly added field, all existing {@link DomainBase#gracePeriods} won't have it until its
 * domain gets loaded. Ths mapreduce job loads all Domain entities and resave them so the {@link
 * GracePeriod#gracePeriodId} can get assigned correctly.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/resaveAllDomains",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ResaveAllDomainsAction implements Runnable {

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;

  @Inject
  ResaveAllDomainsAction() {}

  @Override
  public void run() {
    mrRunner
        .setJobName("Re-save all Domain entities")
        .setModuleName("backend")
        .runMapOnly(
            new ResaveAllDomainsActionMapper(),
            ImmutableList.of(EppResourceInputs.createKeyInput(DomainBase.class)))
        .sendLinkToMapreduceConsole(response);
  }

  /** Mapper to re-save all Domain entities. */
  public static class ResaveAllDomainsActionMapper extends Mapper<Key<DomainBase>, Void, Void> {

    private static final long serialVersionUID = 1L;

    public ResaveAllDomainsActionMapper() {}

    @Override
    public final void map(final Key<DomainBase> domainKey) {
      tm().transact(
              () -> {
                DomainBase domain = ofy().load().key(domainKey).now();
                domain
                    .getGracePeriods()
                    .forEach(
                        gracePeriod -> {
                          if (gracePeriod.getGracePeriodId() == 0L) {
                            throw new IllegalStateException(
                                String.format("gracePeriodId is not set for %s", gracePeriod));
                          }
                        });
                ofy().save().entity(domain).now();
              });
      getContext().incrementCounter("Domain is re-saved");
    }
  }
}
