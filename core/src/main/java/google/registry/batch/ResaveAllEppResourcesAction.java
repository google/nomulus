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

package google.registry.batch;

import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.model.EppResource;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import javax.inject.Inject;

/**
 * A mapreduce that re-saves all EppResources, projecting them forward to the current time.
 *
 * <p>This is useful for completing data migrations on EppResource fields that are accomplished
 * with @OnSave or @OnLoad annotations, and also guarantees that all EppResources will get fresh
 * commit logs (for backup purposes). Additionally, pending actions such as transfers or grace
 * periods that are past their effective time will be resolved.
 *
 * <p>Because there are no auth settings in the {@link Action} annotation, this command can only be
 * run internally, or by pretending to be internal by setting the X-AppEngine-QueueName header,
 * which only admin users can do.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/resaveAllEppResources",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ResaveAllEppResourcesAction implements Runnable {

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject ResaveAllEppResourcesAction() {}

  @Override
  public void run() {
    mrRunner
        .setJobName("Re-save all EPP resources")
        .setModuleName("backend")
        .runMapOnly(
            new ResaveAllEppResourcesActionMapper(),
            ImmutableList.of(EppResourceInputs.createKeyInput(EppResource.class)))
        .sendLinkToMapreduceConsole(response);
  }

  /** Mapper to re-save all EPP resources. */
  public static class ResaveAllEppResourcesActionMapper
      extends Mapper<Key<EppResource>, Void, Void> {

    private static final long serialVersionUID = -7721628665138087001L;
    public ResaveAllEppResourcesActionMapper() {}

    @Override
    public final void map(final Key<EppResource> resourceKey) {
      tm()
          .transact(
              () -> {
                EppResource projectedResource =
                    ofy()
                        .load()
                        .key(resourceKey)
                        .now()
                        .cloneProjectedAtTime(tm().getTransactionTime());
                ofy().save().entity(projectedResource).now();
              });
      getContext().incrementCounter(String.format("%s entities re-saved", resourceKey.getKind()));
    }
  }
}

