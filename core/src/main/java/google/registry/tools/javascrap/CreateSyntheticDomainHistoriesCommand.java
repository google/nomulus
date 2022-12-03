// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.google.appengine.tools.remoteapi.RemoteApiInstaller;
import com.google.appengine.tools.remoteapi.RemoteApiOptions;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.Domain;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.tools.CommandWithConnection;
import google.registry.tools.CommandWithRemoteApi;
import google.registry.tools.ConfirmingCommand;
import google.registry.tools.RemoteApiOptionsUtil;
import google.registry.tools.ServiceConnection;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Command that creates an additional synthetic history object for domains.
 *
 * <p>This is created to fix the issue identified in b/248112997. After b/245940594, there were some
 * domains where the most recent history object did not represent the state of the domain as it
 * exists in the world. Because RDE loads only from DomainHistory objects, this means that RDE was
 * producing wrong data. This command mitigates that issue by creating synthetic history events for
 * every domain that was not deleted as of the start of the bad {@link
 * google.registry.beam.resave.ResaveAllEppResourcesPipeline} -- then, we can guarantee that this
 * new history object represents the state of the domain as far as we know.
 *
 * <p>A previous run of this command (in pipeline form) attempted to do this and succeeded in most
 * cases. Unfortunately, that pipeline had an issue where it used self-allocated IDs for some of the
 * dependent objects (e.g. {@link google.registry.model.domain.secdns.DomainDsDataHistory}). As a
 * result, we want to run this again as a command using Datastore-allocated IDs to re-create
 * synthetic history objects for any domain whose last history object is one of the
 * potentially-incorrect synthetic objects.
 *
 * <p>We further restrict the domains to domains whose latest history object is before October 4.
 * This is an arbitrary date that is suitably far after the previous incorrect run of this synthetic
 * history pipeline, with the purpose of making future runs of this command idempotent (in case the
 * command fails, we can just run it again and again).
 */
@Parameters(
    separators = " =",
    commandDescription = "Create synthetic domain history objects to fix RDE.")
public class CreateSyntheticDomainHistoriesCommand extends ConfirmingCommand
    implements CommandWithRemoteApi, CommandWithConnection {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String HISTORY_REASON =
      "Create synthetic domain histories to fix RDE for b/248112997";
  private static final DateTime BAD_PIPELINE_END_TIME = DateTime.parse("2022-09-10T12:00:00.000Z");
  private static final DateTime NEW_SYNTHETIC_ROUND_START =
      DateTime.parse("2022-10-04T00:00:00.000Z");

  private static final ExecutorService executor = Executors.newFixedThreadPool(20);
  private static final AtomicInteger numDomainsProcessed = new AtomicInteger();

  private ServiceConnection connection;

  @Inject
  @Config("registryAdminClientId")
  String registryAdminRegistrarId;

  @Inject @CredentialModule.LocalCredentialJson String localCredentialJson;

  private final ThreadLocal<RemoteApiInstaller> installerThreadLocal =
      ThreadLocal.withInitial(this::createInstaller);

  private ImmutableSet<String> domainRepoIds;

  @Override
  protected String prompt() {
    jpaTm()
        .transact(
            () -> {
              domainRepoIds =
                  jpaTm()
                      .query(
                          "SELECT dh.domainRepoId FROM DomainHistory dh JOIN Tld t ON t.tldStr ="
                              + " dh.domainBase.tld WHERE t.tldType = 'REAL' AND dh.type ="
                              + " 'SYNTHETIC' AND dh.modificationTime > :badPipelineEndTime AND"
                              + " dh.modificationTime < :newSyntheticRoundStart AND"
                              + " (dh.domainRepoId, dh.modificationTime) IN (SELECT domainRepoId,"
                              + " MAX(modificationTime) FROM DomainHistory GROUP BY domainRepoId)",
                          String.class)
                      .setParameter("badPipelineEndTime", BAD_PIPELINE_END_TIME)
                      .setParameter("newSyntheticRoundStart", NEW_SYNTHETIC_ROUND_START)
                      .getResultStream()
                      .collect(toImmutableSet());
            });
    return String.format(
        "Attempt to create synthetic history entries for %d domains?", domainRepoIds.size());
  }

  @Override
  protected String execute() throws Exception {
    List<Future<?>> futures = new ArrayList<>();
    for (String domainRepoId : domainRepoIds) {
      futures.add(
          executor.submit(
              () -> {
                // Make sure the remote API is installed for ID generation
                installerThreadLocal.get();
                jpaTm()
                    .transact(
                        () -> {
                          Domain domain =
                              jpaTm().loadByKey(VKey.create(Domain.class, domainRepoId));
                          jpaTm()
                              .put(
                                  HistoryEntry.createBuilderForResource(domain)
                                      .setRegistrarId(registryAdminRegistrarId)
                                      .setBySuperuser(true)
                                      .setRequestedByRegistrar(false)
                                      .setModificationTime(jpaTm().getTransactionTime())
                                      .setReason(HISTORY_REASON)
                                      .setType(HistoryEntry.Type.SYNTHETIC)
                                      .build());
                        });
                int numProcessed = numDomainsProcessed.incrementAndGet();
                if (numProcessed % 1000 == 0) {
                  System.out.printf("Saved histories for %d domains%n", numProcessed);
                }
                return null;
              }));
    }
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("Error");
      }
    }
    executor.shutdown();
    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    return String.format("Saved entries for %d domains", numDomainsProcessed.get());
  }

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }

  /**
   * Installs the remote API so that the worker threads can use Datastore for ID generation.
   *
   * <p>Lifted from the RegistryCli class
   */
  private RemoteApiInstaller createInstaller() {
    RemoteApiInstaller installer = new RemoteApiInstaller();
    RemoteApiOptions options = new RemoteApiOptions();
    options.server(connection.getServer().getHost(), getPort(connection.getServer()));
    if (RegistryConfig.areServersLocal()) {
      // Use dev credentials for localhost.
      options.useDevelopmentServerCredential();
    } else {
      try {
        RemoteApiOptionsUtil.useGoogleCredentialStream(
            options, new ByteArrayInputStream(localCredentialJson.getBytes(UTF_8)));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    try {
      installer.install(options);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return installer;
  }

  private static int getPort(URL url) {
    return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
  }
}
