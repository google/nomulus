// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.export.datastore;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.api.client.json.GenericJson;
import com.google.api.client.util.Key;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.export.datastore.DatastoreAdmin.Get;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.util.Clock;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Model object that describes the details of an export or import operation in Cloud Datastore.
 *
 * <p>{@link Operation} instances are parsed from the JSON payload in Datastore response messages.
 */
@DeleteAfterMigration
public class Operation extends GenericJson {

  private static final String STATE_SUCCESS = "SUCCESSFUL";
  private static final String STATE_PROCESSING = "PROCESSING";

  @Key private String name;
  @Key private Metadata metadata;
  @Key private boolean done;

  /** For JSON deserialization. */
  public Operation() {}

  /** Returns the name of this operation, which may be used in a {@link Get} request. */
  public String getName() {
    checkState(name != null, "Name must not be null.");
    return name;
  }

  public boolean isExport() {
    return !isNullOrEmpty(getExportFolderUrl());
  }

  public boolean isImport() {
    return !isNullOrEmpty(getMetadata().getInputUrl());
  }

  public boolean isDone() {
    return done;
  }

  private String getState() {
    return getMetadata().getCommonMetadata().getState();
  }

  public boolean isSuccessful() {
    return getState().equals(STATE_SUCCESS);
  }

  public boolean isProcessing() {
    return getState().equals(STATE_PROCESSING);
  }

  /**
   * Returns the elapsed time since starting if this operation is still running, or the total
   * running time if this operation has completed.
   */
  public Duration getRunningTime(Clock clock) {
    return new Duration(
        getStartTime(), getMetadata().getCommonMetadata().getEndTime().orElse(clock.nowUtc()));
  }

  public DateTime getStartTime() {
    return getMetadata().getCommonMetadata().getStartTime();
  }

  public ImmutableSet<String> getKinds() {
    return ImmutableSet.copyOf(getMetadata().getEntityFilter().getKinds());
  }

  /**
   * Returns the URL to the GCS folder that holds the exported data. This folder is created by
   * Datastore and is under the {@code outputUrlPrefix} set to {@linkplain
   * DatastoreAdmin#export(String, java.util.Collection) the export request}.
   *
   * @throws IllegalStateException if this is not an export operation
   */
  public String getExportFolderUrl() {
    return getMetadata().getOutputUrlPrefix();
  }

  /**
   * Returns the last segment of the {@linkplain #getExportFolderUrl() export folder URL} which can
   * be used as unique identifier of this export operation. This is a better ID than the {@linkplain
   * #getName() operation name}, which is opaque.
   *
   * @throws IllegalStateException if this is not an export operation
   */
  public String getExportId() {
    String exportFolderUrl = getExportFolderUrl();
    return exportFolderUrl.substring(exportFolderUrl.lastIndexOf('/') + 1);
  }

  public String getProgress() {
    StringBuilder result = new StringBuilder();
    Progress progress = getMetadata().getProgressBytes();
    if (progress != null) {
      result.append(
          String.format(" [%s/%s bytes]", progress.workCompleted, progress.workEstimated));
    }
    progress = getMetadata().getProgressEntities();
    if (progress != null) {
      result.append(
          String.format(" [%s/%s entities]", progress.workCompleted, progress.workEstimated));
    }
    if (result.length() == 0) {
      return "Progress: N/A";
    }
    return "Progress:" + result;
  }

  private Metadata getMetadata() {
    checkState(metadata != null, "Response metadata missing.");
    return metadata;
  }

  /** Models the common metadata properties of all operations. */
  public static class CommonMetadata extends GenericJson {

    @Key private String startTime;
    @Key @Nullable private String endTime;
    @Key private String operationType;
    @Key private String state;

    public CommonMetadata() {}

    String getOperationType() {
      checkState(!isNullOrEmpty(operationType), "operationType may not be null or empty");
      return operationType;
    }

    String getState() {
      checkState(!isNullOrEmpty(state), "state may not be null or empty");
      return state;
    }

    DateTime getStartTime() {
      checkState(startTime != null, "StartTime missing.");
      return DateTime.parse(startTime);
    }

    Optional<DateTime> getEndTime() {
      return Optional.ofNullable(endTime).map(DateTime::parse);
    }
  }

  /** Models the metadata of a Cloud Datatore export or import operation. */
  public static class Metadata extends GenericJson {
    @Key("common")
    private CommonMetadata commonMetadata;

    @Key private Progress progressEntities;
    @Key private Progress progressBytes;
    @Key private EntityFilter entityFilter;
    @Key private String inputUrl;
    @Key private String outputUrlPrefix;

    public Metadata() {}

    CommonMetadata getCommonMetadata() {
      checkState(commonMetadata != null, "CommonMetadata field is null.");
      return commonMetadata;
    }

    public Progress getProgressEntities() {
      return progressEntities;
    }

    public Progress getProgressBytes() {
      return progressBytes;
    }

    public EntityFilter getEntityFilter() {
      return entityFilter;
    }

    public String getInputUrl() {
      return checkUrls().inputUrl;
    }

    public String getOutputUrlPrefix() {
      return checkUrls().outputUrlPrefix;
    }

    Metadata checkUrls() {
      checkState(
          isNullOrEmpty(inputUrl) || isNullOrEmpty(outputUrlPrefix),
          "inputUrl and outputUrlPrefix must not be both present");
      checkState(
          !isNullOrEmpty(inputUrl) || !isNullOrEmpty(outputUrlPrefix),
          "inputUrl and outputUrlPrefix must not be both missing");
      return this;
    }
  }

  /** Progress of an export or import operation. */
  public static class Progress extends GenericJson {
    @Key private long workCompleted;
    @Key private long workEstimated;

    public Progress() {}

    long getWorkCompleted() {
      return workCompleted;
    }

    public long getWorkEstimated() {
      return workEstimated;
    }
  }

  /** List of {@link Operation Operations}. */
  public static class OperationList extends GenericJson {
    @Key private List<Operation> operations;

    /** For JSON deserialization. */
    public OperationList() {}

    ImmutableList<Operation> toList() {
      return ImmutableList.copyOf(operations);
    }
  }
}
