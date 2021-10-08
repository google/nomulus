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

package google.registry.rde;

import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static google.registry.beam.BeamUtils.createJobName;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.xml.ValidationMode.LENIENT;
import static google.registry.xml.ValidationMode.STRICT;
import static java.util.function.Function.identity;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.BaseEncoding;
import google.registry.beam.rde.RdePipeline;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryEnvironment;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.mapreduce.inputs.NullInput;
import google.registry.model.EppResource;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.rde.RdeMode;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.xml.ValidationMode;
import java.io.IOException;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action that kicks off either a MapReduce (for Datastore) or Dataflow (for Cloud SQL) job to stage
 * escrow deposit XML files on GCS for RDE/BRDA for all TLDs.
 *
 * <h3>Pending Deposits</h3>
 *
 * <p>This task starts by asking {@link PendingDepositChecker} which deposits need to be generated.
 * If there's nothing to deposit, we return 204 No Content; otherwise, we fire off a MapReduce job
 * and redirect to its status GUI. The task can also be run in manual operation, as described below.
 *
 * <h3>MapReduce</h3>
 *
 * <p>The mapreduce job scans every {@link EppResource} in Datastore. It maps a point-in-time
 * representation of each entity to the escrow XML files in which it should appear.
 *
 * <p>There is one map worker for each {@code EppResourceIndexBucket} entity group shard. There is
 * one reduce worker for each deposit being generated.
 *
 * <p>{@link ContactResource} and {@link HostResource} are emitted on all TLDs, even when the
 * domains on a TLD don't reference them. BRDA {@link RdeMode#THIN thin} deposits exclude contacts
 * and hosts entirely.
 *
 * <p>{@link Registrar} entities, both active and inactive, are included in all deposits. They are
 * not rewinded point-in-time.
 *
 * <h3>Dataflow</h3>
 *
 * The Dataflow job finds the most recent history entry on or before watermark for each resource
 * type and loads the embedded resource from it, which is then projected to watermark time to
 * account for things like pending transfer.
 *
 * <p>Only {@link ContactResource}s and {@link HostResource}s that are referenced by an included
 * {@link DomainBase} will be included in the corresponding pending deposit.
 *
 * <p>{@link Registrar} entities, both active and inactive, are included in all deposits. They are
 * not rewinded point-in-time.
 *
 * <h3>Afterward</h3>
 *
 * <p>The XML deposit files generated by this job are humongous. A tiny XML report file is generated
 * for each deposit, telling us how much of what it contains.
 *
 * <p>Once a deposit is successfully generated, For RDE an {@link RdeUploadAction} is enqueued which
 * will upload it via SFTP to the third-party escrow provider; for BRDA an {@link BrdaCopyAction} is
 * enqueued which will copy it to a GCS bucket and be rsynced to a third-party escrow provider.
 *
 * <p>To generate escrow deposits manually and locally, use the {@code nomulus} tool command {@code
 * GenerateEscrowDepositCommand}.
 *
 * <h3>Logging</h3>
 *
 * <p>To identify the reduce worker request for a deposit in App Engine's log viewer, you can use
 * search text like {@code tld=soy}, {@code watermark=2015-01-01}, and {@code mode=FULL}.
 *
 * <h3>Error Handling</h3>
 *
 * <p>Valid model objects might not be valid to the RDE XML schema. A single invalid object will
 * cause the whole deposit to fail. You need to check the logs, find out which entities are broken,
 * and perform database surgery.
 *
 * <p>If a deposit fails, an error is emitted to the logs for each broken entity. It tells you the
 * key and shows you its representation in lenient XML.
 *
 * <p>Failed deposits will be retried indefinitely. This is because RDE and BRDA each have a {@link
 * Cursor} for each TLD. Even if the cursor lags for days, it'll catch up gradually on its own, once
 * the data becomes valid.
 *
 * <p>The third-party escrow provider will validate each deposit we send them. They do both schema
 * validation and reference checking.
 *
 * <p>This job does not perform reference checking. Administrators can do this locally with the
 * {@code ValidateEscrowDepositCommand} command in the {@code nomulus} tool.
 *
 * <h3>Cursors</h3>
 *
 * <p>Deposits are generated serially for a given (tld, mode) pair. A deposit is never started
 * beyond the cursor. Once a deposit is completed, its cursor is rolled forward transactionally.
 *
 * <p>The mode determines which cursor is used. {@link CursorType#RDE_STAGING} is used for thick
 * deposits and {@link CursorType#BRDA} is used for thin deposits.
 *
 * <p>Use the {@code ListCursorsCommand} and {@code UpdateCursorsCommand} commands to administrate
 * with these cursors.
 *
 * <h3>Security</h3>
 *
 * <p>The deposit and report are encrypted using {@link Ghostryde}. Administrators can use the
 * {@code GhostrydeCommand} command in the {@code nomulus} tool to view them.
 *
 * <p>Unencrypted XML fragments are stored temporarily between the map and reduce steps and between
 * Dataflow transforms. The ghostryde encryption on the full archived deposits makes life a little
 * more difficult for an attacker. But security ultimately depends on the bucket.
 *
 * <h3>Idempotency</h3>
 *
 * <p>We lock the reduce tasks for the MapReduce job. This is necessary because: a) App Engine tasks
 * might get double executed; and b) Cloud Storage file handles get committed on close <i>even if
 * our code throws an exception.</i>
 *
 * <p>For the Dataflow job we do not employ a lock because it is difficult to span a lock across
 * three subsequent transforms (save to GCS, roll forward cursor, enqueue next action). Instead we
 * get around the issue with idempotency by saving the deposit to a unique folder named after the
 * job name so there is no possibility of overwriting.
 *
 * <p>Deposits are generated serially for a given (watermark, mode) pair. A deposit is never started
 * beyond the cursor. Once a deposit is completed, its cursor is rolled forward transactionally.
 * Duplicate jobs may exist {@code <=cursor}. So a transaction will not bother changing the cursor
 * if it's already been rolled forward.
 *
 * <p>Enqueuing {@code RdeUploadAction} or {@code BrdaCopyAction} is also part of the cursor
 * transaction. This is necessary because the first thing the upload task does is check the staging
 * cursor to verify it's been completed, so we can't enqueue before we roll. We also can't enqueue
 * after the roll, because then if enqueuing fails, the upload might never be enqueued.
 *
 * <h3>Determinism</h3>
 *
 * <p>The filename of an escrow deposit is determistic for a given (TLD, watermark, {@linkplain
 * RdeMode mode}) triplet. Its generated contents is deterministic in all the ways that we care
 * about. Its view of the database is strongly consistent in Cloud SQL automatically by nature of
 * the initial query for the history entry running at {@code READ_COMMITTED} transaction isolation
 * level.
 *
 * <p>This is also true in Datastore because:
 *
 * <ol>
 *   <li>{@code EppResource} queries are strongly consistent thanks to {@link EppResourceIndex}
 *   <li>{@code EppResource} entities are rewinded to the point-in-time of the watermark
 * </ol>
 *
 * <p>Here's what's not deterministic:
 *
 * <ul>
 *   <li>Ordering of XML fragments. We don't care about this.
 *   <li>Information about registrars. There's no point-in-time for these objects. So in order to
 *       guarantee referential correctness of your deposits, you must never delete a registrar
 *       entity.
 * </ul>
 *
 * <h3>Manual Operation</h3>
 *
 * <p>The task can be run in manual operation by setting certain parameters. Rather than generating
 * deposits which are currently outstanding, the task will generate specific deposits. The files
 * will be stored in a subdirectory of the "manual" directory, to avoid overwriting regular deposit
 * files. Cursors and revision numbers will not be updated, and the upload task will not be kicked
 * off. The parameters are:
 *
 * <ul>
 *   <li>manual: if present and true, manual operation is indicated
 *   <li>directory: the subdirectory of "manual" into which the files should be placed
 *   <li>mode: the mode(s) to generate: FULL for RDE deposits, THIN for BRDA deposits
 *   <li>tld: the tld(s) for which deposits should be generated
 *   <li>watermark: the date(s) for which deposits should be generated; dates should be start-of-day
 *   <li>revision: optional; if not specified, the next available revision number will be used
 * </ul>
 *
 * <p>The manual, directory, mode, tld and watermark parameters must be present for manual
 * operation; they must all be absent for standard operation (except that manual can be present but
 * set to false). The revision parameter is optional in manual operation, and must be absent for
 * standard operation.
 *
 * @see <a href="https://tools.ietf.org/html/draft-arias-noguchi-registry-data-escrow-06">Registry
 *     Data Escrow Specification</a>
 * @see <a href="https://tools.ietf.org/html/draft-arias-noguchi-dnrd-objects-mapping-05">Domain
 *     Name Registration Data Objects Mapping</a>
 */
@Action(
    service = Action.Service.BACKEND,
    path = RdeStagingAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class RdeStagingAction implements Runnable {

  public static final String PATH = "/_dr/task/rdeStaging";

  private static final String PIPELINE_NAME = "rde_pipeline";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;
  @Inject PendingDepositChecker pendingDepositChecker;
  @Inject RdeStagingReducer.Factory reducerFactory;
  @Inject Response response;
  @Inject GcsUtils gcsUtils;
  @Inject MapreduceRunner mrRunner;
  @Inject @Config("projectId") String projectId;
  @Inject @Config("defaultJobRegion") String jobRegion;

  @Inject
  @Config("highPerformanceMachineType")
  String machineType;

  @Inject @Config("transactionCooldown") Duration transactionCooldown;
  @Inject @Config("beamStagingBucketUrl") String stagingBucketUrl;
  @Inject @Config("rdeBucket") String rdeBucket;
  @Inject @Parameter(RdeModule.PARAM_MANUAL) boolean manual;
  @Inject @Parameter(RdeModule.PARAM_DIRECTORY) Optional<String> directory;
  @Inject @Parameter(RdeModule.PARAM_MODE) ImmutableSet<String> modeStrings;
  @Inject @Parameter(RequestParameters.PARAM_TLDS) ImmutableSet<String> tlds;
  @Inject @Parameter(RdeModule.PARAM_WATERMARKS) ImmutableSet<DateTime> watermarks;
  @Inject @Parameter(RdeModule.PARAM_REVISION) Optional<Integer> revision;
  @Inject @Parameter(RdeModule.PARAM_LENIENT) boolean lenient;
  @Inject @Key("rdeStagingEncryptionKey") byte[] stagingKeyBytes;
  @Inject Dataflow dataflow;
  @Inject RdeStagingAction() {}

  @Override
  public void run() {
    ImmutableSetMultimap<String, PendingDeposit> pendings =
        manual ? getManualPendingDeposits() : getStandardPendingDeposits();
    if (pendings.isEmpty()) {
      String message = "Nothing needs to be deposited.";
      logger.atInfo().log(message);
      response.setStatus(SC_NO_CONTENT);
      // No need to set payload as HTTP 204 response status code does not allow a payload.
      return;
    }
    for (PendingDeposit pending : pendings.values()) {
      logger.atInfo().log("Pending deposit: %s", pending);
    }
    ValidationMode validationMode = lenient ? LENIENT : STRICT;
    if (tm().isOfy()) {
      RdeStagingMapper mapper = new RdeStagingMapper(validationMode, pendings);
      RdeStagingReducer reducer = reducerFactory.create(validationMode, gcsUtils);
      mrRunner
          .setJobName("Stage escrow deposits for all TLDs")
          .setModuleName("backend")
          .setDefaultReduceShards(pendings.size())
          .runMapreduce(
              mapper,
              reducer,
              ImmutableList.of(
                  // Add an extra shard that maps over a null resource. See the mapper code for why.
                  new NullInput<>(), EppResourceInputs.createEntityInput(EppResource.class)))
          .sendLinkToMapreduceConsole(response);
    } else {
      ImmutableList.Builder<String> jobNameBuilder = new ImmutableList.Builder<>();
      pendings.values().stream()
          .collect(toImmutableSetMultimap(PendingDeposit::watermark, identity()))
          .asMap()
          .forEach(
              (watermark, pendingDeposits) -> {
                try {
                  LaunchFlexTemplateParameter parameter =
                      new LaunchFlexTemplateParameter()
                          .setJobName(
                              createJobName(
                                  String.format(
                                      "rde-%s", watermark.toString("yyyy-MM-dd't'HH-mm-ss'z'")),
                                  clock))
                          .setContainerSpecGcsPath(
                              String.format("%s/%s_metadata.json", stagingBucketUrl, PIPELINE_NAME))
                          .setParameters(
                              new ImmutableMap.Builder<String, String>()
                                  .put(
                                      "pendings",
                                      RdePipeline.encodePendingDeposits(
                                          ImmutableSet.copyOf(pendingDeposits)))
                                  .put("validationMode", validationMode.name())
                                  .put("rdeStagingBucket", rdeBucket)
                                  .put(
                                      "stagingKey",
                                      BaseEncoding.base64Url()
                                          .omitPadding()
                                          .encode(stagingKeyBytes))
                                  .put("registryEnvironment", RegistryEnvironment.get().name())
                                  .put("workerMachineType", machineType)
                                  // TODO (jianglai): Investigate turning off public IPs (for which
                                  // there is a quota) in order to increase the total number of
                                  // workers allowed (also under quota).
                                  // See:
                                  // https://cloud.google.com/dataflow/docs/guides/routes-firewall
                                  .put("usePublicIps", "true")
                                  .build());
                  LaunchFlexTemplateResponse launchResponse =
                      dataflow
                          .projects()
                          .locations()
                          .flexTemplates()
                          .launch(
                              projectId,
                              jobRegion,
                              new LaunchFlexTemplateRequest().setLaunchParameter(parameter))
                          .execute();
                  logger.atInfo().log("Got response: %s", launchResponse.getJob().toPrettyString());
                  jobNameBuilder.add(launchResponse.getJob().getId());
                } catch (IOException e) {
                  logger.atWarning().withCause(e).log("Pipeline Launch failed");
                  response.setStatus(SC_INTERNAL_SERVER_ERROR);
                  response.setPayload(String.format("Pipeline launch failed: %s", e.getMessage()));
                }
              });
      response.setStatus(SC_OK);
      response.setPayload(
          String.format("Launched RDE pipeline: %s", Joiner.on(", ").join(jobNameBuilder.build())));
    }
  }

  private ImmutableSetMultimap<String, PendingDeposit> getStandardPendingDeposits() {
    if (directory.isPresent()) {
      throw new BadRequestException("Directory parameter not allowed in standard operation");
    }
    if (!modeStrings.isEmpty()) {
      throw new BadRequestException("Mode parameter not allowed in standard operation");
    }
    if (!tlds.isEmpty()) {
      throw new BadRequestException("Tld parameter not allowed in standard operation");
    }
    if (!watermarks.isEmpty()) {
      throw new BadRequestException("Watermark parameter not allowed in standard operation");
    }
    if (revision.isPresent()) {
      throw new BadRequestException("Revision parameter not allowed in standard operation");
    }

    return ImmutableSetMultimap.copyOf(
        Multimaps.filterValues(
            pendingDepositChecker.getTldsAndWatermarksPendingDepositForRdeAndBrda(),
            pending -> {
              if (clock.nowUtc().isBefore(pending.watermark().plus(transactionCooldown))) {
                logger.atInfo().log(
                    "Ignoring within %s cooldown: %s", transactionCooldown, pending);
                return false;
              } else {
                return true;
              }
            }));
  }

  private ImmutableSetMultimap<String, PendingDeposit> getManualPendingDeposits() {
    if (!directory.isPresent()) {
      throw new BadRequestException("Directory parameter required in manual operation");
    }
    if (directory.get().startsWith("/")) {
      throw new BadRequestException("Directory must not start with a slash");
    }
    String directoryWithTrailingSlash =
        directory.get().endsWith("/") ? directory.get() : directory.get() + '/';

    if (modeStrings.isEmpty()) {
      throw new BadRequestException("Mode parameter required in manual operation");
    }

    ImmutableSet.Builder<RdeMode> modesBuilder = new ImmutableSet.Builder<>();
    for (String modeString : modeStrings) {
      try {
        modesBuilder.add(RdeMode.valueOf(Ascii.toUpperCase(modeString)));
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("Mode must be FULL for RDE deposits, THIN for BRDA deposits");
      }
    }
    ImmutableSet<RdeMode> modes = modesBuilder.build();

    if (tlds.isEmpty()) {
      throw new BadRequestException("Tld parameter required in manual operation");
    }

    if (watermarks.isEmpty()) {
      throw new BadRequestException("Watermark parameter required in manual operation");
    }
    // In theory, BRDA deposits should be on a specific day of the week, but in manual mode, let the
    // user create deposits on other days. But dates should definitely be at the start of the day;
    // otherwise, confusion is likely.
    for (DateTime watermark : watermarks) {
      if (!watermark.equals(watermark.withTimeAtStartOfDay())) {
        throw new BadRequestException("Watermarks must be at the start of a day.");
      }
    }

    if (revision.isPresent() && revision.get() < 0) {
      throw new BadRequestException("Revision must be greater than or equal to zero");
    }

    ImmutableSetMultimap.Builder<String, PendingDeposit> pendingsBuilder =
        new ImmutableSetMultimap.Builder<>();

    for (String tld : tlds) {
      for (DateTime watermark : watermarks) {
        for (RdeMode mode : modes) {
          pendingsBuilder.put(
              tld,
              PendingDeposit.createInManualOperation(
                  tld, watermark, mode, directoryWithTrailingSlash, revision.orElse(null)));
        }
      }
    }

    return pendingsBuilder.build();
  }
}
