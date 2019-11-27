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

package google.registry.export;

import static com.google.appengine.tools.cloudstorage.GcsServiceFactory.createGcsService;
import static com.google.common.base.Verify.verifyNotNull;
import static google.registry.mapreduce.inputs.EppResourceInputs.createEntityInput;
import static google.registry.model.EppResourceUtils.isActive;
import static google.registry.model.registry.Registries.getTldsOfType;
import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.ReducerInput;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.domain.DomainBase;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldType;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import google.registry.util.NonFinalForTesting;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.function.Supplier;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * A mapreduce that exports the list of active domains on all real TLDs to Google Drive and GCS.
 *
 * <p>Each TLD's active domain names are exported as a newline-delimited flat text file with the
 * name TLD.txt into the domain-lists bucket. Note that this overwrites the files in place.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/exportDomainLists",
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ExportDomainListsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int MAX_NUM_REDUCE_SHARDS = 100;

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject @Config("domainListsGcsBucket") String gcsBucket;
  @Inject @Config("gcsBufferSize") int gcsBufferSize;
  @Inject ExportDomainListsAction() {}

  @Override
  public void run() {
    ImmutableSet<String> realTlds = getTldsOfType(TldType.REAL);
    logger.atInfo().log("Exporting domain lists for tlds %s", realTlds);
    mrRunner
        .setJobName("Export domain lists")
        .setModuleName("backend")
        .setDefaultReduceShards(Math.min(realTlds.size(), MAX_NUM_REDUCE_SHARDS))
        .runMapreduce(
            new ExportDomainListsMapper(DateTime.now(UTC), realTlds),
            new ExportDomainListsReducer(gcsBucket, gcsBufferSize),
            ImmutableList.of(createEntityInput(DomainBase.class)))
        .sendLinkToMapreduceConsole(response);
  }

  static class ExportDomainListsMapper extends Mapper<DomainBase, String, String> {

    private static final long serialVersionUID = -7312206212434039854L;

    private final DateTime exportTime;
    private final ImmutableSet<String> realTlds;

    ExportDomainListsMapper(DateTime exportTime, ImmutableSet<String> realTlds) {
      this.exportTime = exportTime;
      this.realTlds = realTlds;
    }

    @Override
    public void map(DomainBase domain) {
      if (realTlds.contains(domain.getTld()) && isActive(domain, exportTime)) {
        emit(domain.getTld(), domain.getFullyQualifiedDomainName());
        getContext().incrementCounter(String.format("domains in tld %s", domain.getTld()));
      }
    }
  }

  static class ExportDomainListsReducer extends Reducer<String, String, Void> {

    private static final long serialVersionUID = 7035260977259119087L;

    /** Allows overriding the default {@link DriveConnection} in tests. */
    @NonFinalForTesting
    private static Supplier<DriveConnection> driveConnectionSupplier =
        Suppliers.memoize(() -> DaggerDriveModule_DriveComponent.create().driveConnection());

    static final String REGISTERED_DOMAINS_FILENAME = "registered_domains.txt";
    static final MediaType EXPORT_MIME_TYPE = MediaType.PLAIN_TEXT_UTF_8;

    private final String gcsBucket;
    private final int gcsBufferSize;

    /**
     * Non-serializable {@link DriveConnection} that will be created when an instance of {@link
     * ExportDomainListsReducer} is deserialized in a MR pipeline worker.
     *
     * <p>See {@link #readObject(ObjectInputStream)}.
     */
    private transient DriveConnection driveConnection;

    public ExportDomainListsReducer(String gcsBucket, int gcsBufferSize) {
      this.gcsBucket = gcsBucket;
      this.gcsBufferSize = gcsBufferSize;
    }

    @SuppressWarnings("unused")
    private void readObject(ObjectInputStream is) throws IOException, ClassNotFoundException {
      is.defaultReadObject();
      driveConnection = driveConnectionSupplier.get();
    }

    private void exportToDrive(String tld, String domains) {
      verifyNotNull(driveConnection, "expecting non-null driveConnection");
      try {
        Registry registry = Registry.get(tld);
        if (registry.getDriveFolderId() == null) {
          logger.atInfo().log(
              "Skipping registered domains export for TLD %s because Drive folder isn't specified",
              tld);
        } else {
          String resultMsg =
              driveConnection.createOrUpdateFile(
                  REGISTERED_DOMAINS_FILENAME,
                  EXPORT_MIME_TYPE,
                  registry.getDriveFolderId(),
                  domains.getBytes(UTF_8));
          logger.atInfo().log(
              "Exporting registered domains succeeded for TLD %s, response was: %s",
              tld, resultMsg);
        }
      } catch (Throwable e) {
        logger.atSevere().withCause(e).log(
            "Error exporting registered domains for TLD %s to Drive", tld);
      }
      getContext().incrementCounter("domain lists written out to Drive");
    }

    private void exportToGcs(String tld, String domains) {
      GcsFilename filename = new GcsFilename(gcsBucket, tld + ".txt");
      GcsUtils cloudStorage =
          new GcsUtils(createGcsService(RetryParams.getDefaultInstance()), gcsBufferSize);
      try (OutputStream gcsOutput = cloudStorage.openOutputStream(filename);
          Writer osWriter = new OutputStreamWriter(gcsOutput, UTF_8)) {
        osWriter.write(domains);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log(
            "Error exporting registered domains for TLD %s to GCS.", tld);
      }
      getContext().incrementCounter("domain lists written out to GCS");
    }

    @Override
    public void reduce(String tld, ReducerInput<String> fqdns) {
      ImmutableList<String> domains = ImmutableList.sortedCopyOf(() -> fqdns);
      String domainsList = Joiner.on('\n').join(domains);
      logger.atInfo().log("Exporting %d domains for TLD %s to GCS and Drive.", domains.size(), tld);
      exportToGcs(tld, domainsList);
      exportToDrive(tld, domainsList);
    }

    @VisibleForTesting
    static void setDriveConnectionForTesting(
        Supplier<DriveConnection> testDriveConnectionSupplier) {
      driveConnectionSupplier = testDriveConnectionSupplier;
    }
  }
}
