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

package google.registry.beam.comparedb;

import com.google.auto.value.AutoValue;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import dagger.Component;
import google.registry.config.CloudTasksUtilsModule;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.util.Clock;
import google.registry.util.UtilsModule;
import java.io.IOException;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.joda.time.DateTime;
import org.joda.time.Instant;
import org.joda.time.Interval;

/** Finds the necessary information for loading the most recent Datastore snapshot. */
@DeleteAfterMigration
public class LatestDatastoreSnapshotFinder {
  private final String projectId;
  private final GcsUtils gcsUtils;
  private final Clock clock;

  @Inject
  LatestDatastoreSnapshotFinder(
      @Config("projectId") String projectId, GcsUtils gcsUtils, Clock clock) {
    this.projectId = projectId;
    this.gcsUtils = gcsUtils;
    this.clock = clock;
  }

  /**
   * Finds information of the most recent Datastore snapshot that ends strictly before {@code
   * exportEndTimeUpperBound}, including the GCS folder of the exported data files and the start and
   * stop times of the export. The folder of the CommitLogs is also included in the return.
   */
  public DatastoreSnapshotInfo getSnapshotInfo(Instant exportEndTimeUpperBound) {
    String bucketName = RegistryConfig.getDatastoreBackupsBucket().substring("gs://".length());
    /**
     * Find the bucket-relative path to the overall metadata file of the last Datastore export.
     * Since Datastore export is saved daily, we may need to look back to yesterday. If found, the
     * return value is like
     * "2021-11-19T06:00:00_76493/2021-11-19T06:00:00_76493.overall_export_metadata".
     */
    Optional<String> metaFilePathOptional =
        findNewestExportMetadataFileBeforeTime(bucketName, exportEndTimeUpperBound, 2);
    if (!metaFilePathOptional.isPresent()) {
      throw new NoSuchElementException("No exports found over the past 2 days.");
    }
    String metaFilePath = metaFilePathOptional.get();
    String metaFileFolder = metaFilePath.substring(0, metaFilePath.indexOf('/'));
    Instant exportStartTime = Instant.parse(metaFileFolder.replace('_', '.') + 'Z');
    BlobInfo blobInfo = gcsUtils.getBlobInfo(BlobId.of(bucketName, metaFilePath));
    Instant exportEndTime = new Instant(blobInfo.getCreateTime());
    return DatastoreSnapshotInfo.create(
        String.format("gs://%s/%s", bucketName, metaFileFolder),
        getCommitLogDir(),
        new Interval(exportStartTime, exportEndTime));
  }

  public String getCommitLogDir() {
    return "gs://" + projectId + "-commits";
  }

  /**
   * Finds the latest Datastore export that ends strictly before {@code endTimeUpperBound} and
   * returns the bucket-relative path of the overall export metadata file, in the given bucket. The
   * search goes back for up to {@code lookBackDays} days in time, including today.
   *
   * <p>The overall export metadata file is the last file created during a Datastore export. All
   * data has been exported by the creation time of this file. The name of this file, like that of
   * all files in the same export, begins with the timestamp when the export starts.
   *
   * <p>An example return value: {@code
   * 2021-11-19T06:00:00_76493/2021-11-19T06:00:00_76493.overall_export_metadata}.
   */
  private Optional<String> findNewestExportMetadataFileBeforeTime(
      String bucketName, Instant endTimeUpperBound, int lookBackDays) {
    DateTime today = clock.nowUtc();
    for (int day = 0; day < lookBackDays; day++) {
      String dateString = today.minusDays(day).toString("yyyy-MM-dd");
      try {
        Optional<String> metaFilePath =
            gcsUtils.listFolderObjects(bucketName, dateString).stream()
                .filter(s -> s.endsWith("overall_export_metadata"))
                .map(s -> dateString + s)
                .sorted(Comparator.<String>naturalOrder().reversed())
                .findFirst();
        if (metaFilePath.isPresent()) {
          BlobInfo blobInfo = gcsUtils.getBlobInfo(BlobId.of(bucketName, metaFilePath.get()));
          Instant exportEndTime = new Instant(blobInfo.getCreateTime());
          if (exportEndTime.isBefore(endTimeUpperBound)) {
            return metaFilePath;
          }
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
    return Optional.empty();
  }

  /** Holds information about a Datastore snapshot. */
  @AutoValue
  abstract static class DatastoreSnapshotInfo {
    abstract String exportDir();

    abstract String commitLogDir();

    abstract Interval exportInterval();

    static DatastoreSnapshotInfo create(
        String exportDir, String commitLogDir, Interval exportOperationInterval) {
      return new AutoValue_LatestDatastoreSnapshotFinder_DatastoreSnapshotInfo(
          exportDir, commitLogDir, exportOperationInterval);
    }
  }

  @Singleton
  @Component(
      modules = {
        CredentialModule.class,
        ConfigModule.class,
        CloudTasksUtilsModule.class,
        UtilsModule.class
      })
  interface LatestDatastoreSnapshotFinderFinderComponent {

    LatestDatastoreSnapshotFinder datastoreSnapshotInfoFinder();
  }
}
