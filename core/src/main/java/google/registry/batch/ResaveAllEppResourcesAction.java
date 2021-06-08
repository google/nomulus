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

import static google.registry.mapreduce.MapreduceRunner.PARAM_FAST;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.model.EppResource;
import google.registry.request.Action;
import google.registry.request.Parameter;
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
 *
 * <p>If the <code>?fast=true</code> querystring parameter is passed, then entities that are not
 * changed by {@link EppResource#cloneProjectedAtTime} will not be re-saved. This helps prevent
 * mutation load on the DB and has the beneficial side effect of writing out smaller commit logs.
 * Note that this does NOT pick up mutations caused by migrations using the {@link
 * com.googlecode.objectify.annotation.OnLoad} annotation, so if you are running a one-off schema
 * migration, do not use fast mode. Fast mode defaults to false for this reason, but is used by the
 * monthly invocation of the mapreduce.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/resaveAllEppResources",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
// No longer needed in SQL. Subject to future removal.
@Deprecated
public class ResaveAllEppResourcesAction implements Runnable {

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;

  @Inject
  @Parameter(PARAM_FAST)
  boolean isFast;

  @Inject
  ResaveAllEppResourcesAction() {}

  /**
   * The number of shards to run the map-only mapreduce on.
   *
   * <p>This is less than the default of 100 because we only run this action monthly and can afford
   * it being slower, but we don't want to write out lots of large commit logs in a short period of
   * time because they make the Cloud SQL migration tougher.
   */
  private static final int NUM_SHARDS = 10;

  @Override
  public void run() {
    mrRunner
        .setJobName("Re-save all EPP resources")
        .setModuleName("backend")
        .setDefaultMapShards(NUM_SHARDS)
        .runMapOnly(
            new ResaveAllEppResourcesActionMapper(isFast),
            ImmutableList.of(EppResourceInputs.createKeyInput(EppResource.class)))
        .sendLinkToMapreduceConsole(response);
  }

  /** Mapper to re-save all EPP resources. */
  public static class ResaveAllEppResourcesActionMapper
      extends Mapper<Key<EppResource>, Void, Void> {

    private static final long serialVersionUID = -7721628665138087001L;

    private final boolean isFast;

    ResaveAllEppResourcesActionMapper(boolean isFast) {
      this.isFast = isFast;
    }

    @Override
    public final void map(final Key<EppResource> resourceKey) {
      boolean resaved =
          tm().transact(
                  () -> {
                    EppResource originalResource = auditedOfy().load().key(resourceKey).now();
                    EppResource projectedResource =
                        originalResource.cloneProjectedAtTime(tm().getTransactionTime());
                    if (isFast && originalResource.equals(projectedResource)) {
                      return false;
                    } else {
                      auditedOfy().save().entity(projectedResource).now();
                      return true;
                    }
                  });
      getContext()
          .incrementCounter(
              String.format(
                  "%s entities %s",
                  resourceKey.getKind(), resaved ? "re-saved" : "with no changes skipped"));
    }
  }
}
