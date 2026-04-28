// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.model.common.Cursor.CursorType.REMOTE_CACHE_DOMAIN_SYNC;
import static google.registry.model.common.Cursor.CursorType.REMOTE_CACHE_HOST_SYNC;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.cache.SimplifiedJedisClient;
import google.registry.model.EppResource;
import google.registry.model.common.Cursor;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tlds;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.joda.time.Duration;

@Action(
    service = Action.Service.BACKEND,
    path = SyncRemoteCacheAction.PATH,
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class SyncRemoteCacheAction implements Runnable {

  public static final String PATH = "/_dr/task/syncRemoteCache";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String LOCK_NAME = "syncRemoteCacheAction";
  private static final int BATCH_SIZE = 10000;

  private final LockHandler lockHandler;
  private final Response response;
  private final Optional<SimplifiedJedisClient<Domain>> domainJedisClient;
  private final Optional<SimplifiedJedisClient<Host>> hostJedisClient;

  @Inject
  public SyncRemoteCacheAction(
      LockHandler lockHandler,
      Response response,
      Optional<SimplifiedJedisClient<Domain>> domainJedisClient,
      Optional<SimplifiedJedisClient<Host>> hostJedisClient) {
    this.lockHandler = lockHandler;
    this.response = response;
    this.domainJedisClient = domainJedisClient;
    this.hostJedisClient = hostJedisClient;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    if (domainJedisClient.isEmpty() || hostJedisClient.isEmpty()) {
      response.setStatus(SC_NO_CONTENT);
      response.setPayload("No Jedis/Valkey configuration found");
      return;
    }
    Callable<Void> runner =
        () -> {
          try {
            runLocked();
            response.setStatus(SC_OK);
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Errored out during execution.");
            response.setStatus(SC_INTERNAL_SERVER_ERROR);
            response.setPayload(String.format("Errored out with cause: %s", e));
          }
          return null;
        };

    if (!lockHandler.executeWithLocks(runner, null, Duration.standardHours(1), LOCK_NAME)) {
      // Send a 200-series status code to prevent this conflicting action from retrying.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload("Could not acquire lock; already running?");
    }
  }

  private void runLocked() {
    // Note: the transaction ordering means that cursors in our database are updated only if all the
    // operations for the entity type succeeded. There is no downside to processing the same objects
    // multiple times if, for some reason, saving the cursor to the DB fails.
    int syncedDomains = tm().transact(this::syncDomains);
    int syncedHosts = tm().transact(this::syncHosts);
    String message = String.format("Synced %d domains and %d hosts.", syncedDomains, syncedHosts);
    logger.atInfo().log(message);
    response.setPayload(message);
  }

  private int syncDomains() {
    Instant domainCursorTime = getPreviousCursorTime(REMOTE_CACHE_DOMAIN_SYNC);
    ImmutableSet<String> realTlds = Tlds.getTldsOfType(Tld.TldType.REAL);
    List<Domain> domains =
        tm().query(
                "FROM Domain WHERE updateTimestamp.lastUpdateTime > :cursorTime AND tld IN"
                    + " :realTlds ORDER BY updateTimestamp ASC",
                Domain.class)
            .setParameter("cursorTime", domainCursorTime)
            .setParameter("realTlds", realTlds)
            .setMaxResults(BATCH_SIZE)
            .getResultList();
    if (domains.isEmpty()) {
      logger.atInfo().log("No domains to process");
      return 0;
    }
    logger.atInfo().log("Processing %d domains", domains.size());
    processResources(domainJedisClient.get(), domains, Domain::getDomainName);
    setNewCursorTime(domains, REMOTE_CACHE_DOMAIN_SYNC);
    return domains.size();
  }

  private int syncHosts() {
    Instant hostCursorTime = getPreviousCursorTime(REMOTE_CACHE_HOST_SYNC);
    List<Host> hosts =
        tm().query(
                "FROM Host WHERE updateTimestamp.lastUpdateTime > :cursorTime ORDER BY"
                    + " updateTimestamp ASC",
                Host.class)
            .setParameter("cursorTime", hostCursorTime)
            .setMaxResults(BATCH_SIZE)
            .getResultList();
    if (hosts.isEmpty()) {
      logger.atInfo().log("No hosts to process");
      return 0;
    }
    logger.atInfo().log("Processing %d hosts", hosts.size());
    processResources(hostJedisClient.get(), hosts, Host::getRepoId);
    setNewCursorTime(hosts, REMOTE_CACHE_HOST_SYNC);
    return hosts.size();
  }

  private <T extends EppResource> void processResources(
      SimplifiedJedisClient<T> jedisClient, List<T> resources, Function<T, String> getKeyFunction) {
    ImmutableList.Builder<String> toDeleteBuilder = new ImmutableList.Builder<>();
    ImmutableList.Builder<SimplifiedJedisClient.JedisResource<T>> toSaveBuilder =
        new ImmutableList.Builder<>();

    for (T resource : resources) {
      String key = getKeyFunction.apply(resource);
      if (resource.getDeletionTime().isAfter(tm().getTxTime())) {
        toSaveBuilder.add(new SimplifiedJedisClient.JedisResource<>(key, resource));
      } else {
        toDeleteBuilder.add(key);
      }
    }
    ImmutableList<String> toDelete = toDeleteBuilder.build();
    ImmutableList<SimplifiedJedisClient.JedisResource<T>> toSave = toSaveBuilder.build();

    jedisClient.deleteAll(toDelete);
    logger.atInfo().log("Invalidated %d from the remote cache", toDelete.size());
    jedisClient.setAll(toSave);
    logger.atInfo().log("Set %d in the remote cache", toSave.size());
  }

  private Instant getPreviousCursorTime(Cursor.CursorType cursorType) {
    return tm().loadByKeyIfPresent(Cursor.createGlobalVKey(cursorType))
        .map(Cursor::getCursorTimeInstant)
        .orElse(START_INSTANT);
  }

  private void setNewCursorTime(
      List<? extends EppResource> resources, Cursor.CursorType cursorType) {
    Instant lastUpdateTime = Iterables.getLast(resources).getUpdateTimestamp().getTimestamp();
    tm().put(Cursor.createGlobal(cursorType, lastUpdateTime));
    logger.atInfo().log("Set new %s cursor time to %s", cursorType, lastUpdateTime);
  }
}
