// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.rde;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static google.registry.model.common.Cursor.getCursorTimeOrStartOfTime;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.rde.RdeModule.BRDA_QUEUE;
import static google.registry.rde.RdeModule.RDE_UPLOAD_QUEUE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auto.value.AutoValue;
import com.google.cloud.storage.BlobId;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.flogger.FluentLogger;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.PgpHelper;
import google.registry.model.common.Cursor;
import google.registry.model.rde.RdeMode;
import google.registry.model.rde.RdeNamingUtils;
import google.registry.model.rde.RdeRevision;
import google.registry.model.tld.Registry;
import google.registry.rde.BrdaCopyAction;
import google.registry.rde.DepositFragment;
import google.registry.rde.Ghostryde;
import google.registry.rde.PendingDeposit;
import google.registry.rde.RdeCounter;
import google.registry.rde.RdeMarshaller;
import google.registry.rde.RdeModule;
import google.registry.rde.RdeResourceType;
import google.registry.rde.RdeUploadAction;
import google.registry.rde.RdeUtil;
import google.registry.request.Action.Service;
import google.registry.request.RequestParameters;
import google.registry.tldconfig.idn.IdnTableEnum;
import google.registry.util.CloudTasksUtils;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdeheader.XjcRdeHeaderElement;
import google.registry.xml.ValidationMode;
import google.registry.xml.XmlException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.Security;
import java.util.Optional;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;

public class RdeIO {

  @AutoValue
  abstract static class Write
      extends PTransform<PCollection<KV<PendingDeposit, Iterable<DepositFragment>>>, PDone> {

    private static final long serialVersionUID = 3334807737227087760L;

    abstract GcsUtils gcsUtils();

    abstract CloudTasksUtils cloudTasksUtils();

    abstract String rdeBucket();

    // It's OK to return a primitive array because we are only using it to construct the
    // PGPPublicKey, which is not serializable.
    @SuppressWarnings("mutable")
    abstract byte[] stagingKeyBytes();

    abstract ValidationMode validationMode();

    static Builder builder() {
      return new AutoValue_RdeIO_Write.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setGcsUtils(GcsUtils value);

      abstract Builder setCloudTasksUtils(CloudTasksUtils value);

      abstract Builder setRdeBucket(String value);

      abstract Builder setStagingKeyBytes(byte[] value);

      abstract Builder setValidationMode(ValidationMode value);

      abstract Write build();
    }

    @Override
    public PDone expand(PCollection<KV<PendingDeposit, Iterable<DepositFragment>>> input) {
      input
          .apply(
              "Write to GCS",
              ParDo.of(new RdeWriter(gcsUtils(), rdeBucket(), stagingKeyBytes(), validationMode())))
          .apply(
              "Update cursor and enqueue next action",
              ParDo.of(new CursorUpdater(cloudTasksUtils())));
      return PDone.in(input.getPipeline());
    }
  }

  private static class RdeWriter
      extends DoFn<KV<PendingDeposit, Iterable<DepositFragment>>, KV<PendingDeposit, Integer>> {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private static final long serialVersionUID = 5496375923068400382L;

    private final GcsUtils gcsUtils;
    private final String rdeBucket;
    private final byte[] stagingKeyBytes;
    private final RdeMarshaller marshaller;

    protected RdeWriter(
        GcsUtils gcsUtils,
        String rdeBucket,
        byte[] stagingKeyBytes,
        ValidationMode validationMode) {
      this.gcsUtils = gcsUtils;
      this.rdeBucket = rdeBucket;
      this.stagingKeyBytes = stagingKeyBytes;
      this.marshaller = new RdeMarshaller(validationMode);
    }

    @Setup
    public void setup() {
      Security.addProvider(new BouncyCastleProvider());
    }

    @ProcessElement
    public void processElement(
        @Element KV<PendingDeposit, Iterable<DepositFragment>> kv,
        PipelineOptions options,
        OutputReceiver<KV<PendingDeposit, Integer>> outputReceiver) {
      PGPPublicKey stagingKey = PgpHelper.loadPublicKeyBytes(stagingKeyBytes);
      PendingDeposit key = kv.getKey();
      Iterable<DepositFragment> fragments = kv.getValue();
      RdeCounter counter = new RdeCounter();

      // Determine some basic things about the deposit.
      final RdeMode mode = key.mode();
      final String tld = key.tld();
      final DateTime watermark = key.watermark();
      final int revision =
          Optional.ofNullable(key.revision())
              .orElseGet(() -> RdeRevision.getNextRevision(tld, watermark, mode));
      String id = RdeUtil.timestampToId(watermark);
      String prefix =
          options.getJobName()
              + '/'
              + RdeNamingUtils.makeRydeFilename(tld, watermark, mode, 1, revision);
      if (key.manual()) {
        checkState(key.directoryWithTrailingSlash() != null, "Manual subdirectory not specified");
        prefix = "manual/" + key.directoryWithTrailingSlash() + prefix;
      }
      BlobId xmlFilename = BlobId.of(rdeBucket, prefix + ".xml.ghostryde");
      // This file will contain the byte length (ASCII) of the raw unencrypted XML.
      //
      // This is necessary because RdeUploadAction creates a tar file which requires that the length
      // be outputted. We don't want to have to decrypt the entire ghostryde file to determine the
      // length, so we just save it separately.
      BlobId xmlLengthFilename = BlobId.of(rdeBucket, prefix + ".xml.length");
      BlobId reportFilename = BlobId.of(rdeBucket, prefix + "-report.xml.ghostryde");

      // These variables will be populated as we write the deposit XML and used for other files.
      boolean failed = false;
      XjcRdeHeader header;

      // Write a gigantic XML file to GCS. We'll start by opening encrypted out/err file handles.

      logger.atInfo().log("Writing files '%s' and '%s'.", xmlFilename, xmlLengthFilename);
      try (OutputStream gcsOutput = gcsUtils.openOutputStream(xmlFilename);
          OutputStream lengthOutput = gcsUtils.openOutputStream(xmlLengthFilename);
          OutputStream ghostrydeEncoder = Ghostryde.encoder(gcsOutput, stagingKey, lengthOutput);
          Writer output = new OutputStreamWriter(ghostrydeEncoder, UTF_8)) {

        // Output the top portion of the XML document.
        output.write(marshaller.makeHeader(id, watermark, RdeResourceType.getUris(mode), revision));

        // Output XML fragments while counting them.
        for (DepositFragment fragment : fragments) {
          if (!fragment.xml().isEmpty()) {
            output.write(fragment.xml());
            counter.increment(fragment.type());
          }
          if (!fragment.error().isEmpty()) {
            failed = true;
            logger.atSevere().log("Fragment error: %s", fragment.error());
          }
        }

        // Don't write the IDN elements for BRDA.
        if (mode == RdeMode.FULL) {
          for (IdnTableEnum idn : IdnTableEnum.values()) {
            output.write(marshaller.marshalIdn(idn.getTable()));
            counter.increment(RdeResourceType.IDN);
          }
        }

        // Output XML that says how many resources were emitted.
        header = counter.makeHeader(tld, mode);
        output.write(marshaller.marshalOrDie(new XjcRdeHeaderElement(header)));

        // Output the bottom of the XML document.
        output.write(marshaller.makeFooter());

      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // If an entity was broken, abort after writing as much logs/deposit data as possible.
      verify(!failed, "RDE staging failed for TLD %s", tld);

      // Write a tiny XML file to GCS containing some information about the deposit.
      //
      // This will be sent to ICANN once we're done uploading the big XML to the escrow provider.
      if (mode == RdeMode.FULL) {
        logger.atInfo().log("Writing file '%s'.", reportFilename);
        try (OutputStream gcsOutput = gcsUtils.openOutputStream(reportFilename);
            OutputStream ghostrydeEncoder = Ghostryde.encoder(gcsOutput, stagingKey)) {
          counter.makeReport(id, watermark, header, revision).marshal(ghostrydeEncoder, UTF_8);
        } catch (IOException | XmlException e) {
          throw new RuntimeException(e);
        }
      }
      // Now that we're done, output roll the cursor forward.
      if (key.manual()) {
        logger.atInfo().log("Manual operation; not advancing cursor or enqueuing upload task.");
        // Temporary measure to run RDE in beam in parallel with the daily MapReduce based RDE runs.
      } else if (tm().isOfy()) {
        logger.atInfo().log("Ofy is primary TM; not advancing cursor or enqueuing upload task.");
      } else {
        outputReceiver.output(KV.of(key, revision));
      }
    }
  }

  private static class CursorUpdater extends DoFn<KV<PendingDeposit, Integer>, Void> {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private static final long serialVersionUID = 5822176227753327224L;

    private final CloudTasksUtils cloudTasksUtils;

    private CursorUpdater(CloudTasksUtils cloudTasksUtils) {
      this.cloudTasksUtils = cloudTasksUtils;
    }

    @ProcessElement
    public void processElement(
        @Element KV<PendingDeposit, Integer> input, PipelineOptions options) {
      tm().transact(
              () -> {
                PendingDeposit key = input.getKey();
                int revision = input.getValue();
                Registry registry = Registry.get(key.tld());
                Optional<Cursor> cursor =
                    transactIfJpaTm(
                        () ->
                            tm().loadByKeyIfPresent(
                                    Cursor.createVKey(key.cursor(), registry.getTldStr())));
                DateTime position = getCursorTimeOrStartOfTime(cursor);
                checkState(key.interval() != null, "Interval must be present");
                DateTime newPosition = key.watermark().plus(key.interval());
                if (!position.isBefore(newPosition)) {
                  logger.atWarning().log("Cursor has already been rolled forward.");
                  return;
                }
                verify(
                    position.equals(key.watermark()),
                    "Partial ordering of RDE deposits broken: %s %s",
                    position,
                    key);
                tm().put(Cursor.create(key.cursor(), newPosition, registry));
                logger.atInfo().log(
                    "Rolled forward %s on %s cursor to %s.", key.cursor(), key.tld(), newPosition);
                RdeRevision.saveRevision(key.tld(), key.watermark(), key.mode(), revision);
                if (key.mode() == RdeMode.FULL) {
                  cloudTasksUtils.enqueue(
                      RDE_UPLOAD_QUEUE,
                      CloudTasksUtils.createPostTask(
                          RdeUploadAction.PATH,
                          Service.BACKEND.getServiceId(),
                          ImmutableMultimap.of(
                              RequestParameters.PARAM_TLD,
                              key.tld(),
                              RdeModule.PARAM_PREFIX,
                              options.getJobName() + '/')));
                } else {
                  cloudTasksUtils.enqueue(
                      BRDA_QUEUE,
                      CloudTasksUtils.createPostTask(
                          BrdaCopyAction.PATH,
                          Service.BACKEND.getServiceId(),
                          ImmutableMultimap.of(
                              RequestParameters.PARAM_TLD,
                              key.tld(),
                              RdeModule.PARAM_WATERMARK,
                              key.watermark().toString(),
                              RdeModule.PARAM_PREFIX,
                              options.getJobName() + '/')));
                }
              });
    }
  }
}
