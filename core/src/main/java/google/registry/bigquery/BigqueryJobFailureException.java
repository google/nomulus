// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bigquery;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.bigquery.model.ErrorProto;
import com.google.api.services.bigquery.model.JobStatus;
import com.google.common.collect.Iterables;
import java.io.IOException;
import javax.annotation.Nullable;

/** Generic exception to throw if a Bigquery job fails. */
public final class BigqueryJobFailureException extends RuntimeException {

  /** Delegate {@link IOException} errors, checking for {@link GoogleJsonResponseException} */
  public static BigqueryJobFailureException create(IOException cause) {
    if (cause instanceof GoogleJsonResponseException) {
        return create((GoogleJsonResponseException) cause);
    } else {
      return new BigqueryJobFailureException(cause.getMessage(), cause, null, null);
    }
  }

  /** Create an error for JSON server response errors. */
  public static BigqueryJobFailureException create(GoogleJsonResponseException cause) {
    GoogleJsonError err = cause.getDetails();
    if (err != null) {
      return new BigqueryJobFailureException(err.getMessage(), null, null, err);
    } else {
      return new BigqueryJobFailureException(cause.getMessage(), cause, null, null);
    }
  }

  /** Create an error from a failed job. */
  public static BigqueryJobFailureException create(JobStatus jobStatus) {
    checkArgument(jobStatus.getErrorResult() != null, "this job didn't fail!");
    return new BigqueryJobFailureException(
        describeError(jobStatus.getErrorResult()), null, jobStatus, null);
  }

  @Nullable
  private final JobStatus jobStatus;

  @Nullable
  private final GoogleJsonError jsonError;

  public BigqueryJobFailureException(
      String message,
      @Nullable Throwable cause,
      @Nullable JobStatus jobStatus,
      @Nullable GoogleJsonError jsonError) {
    super(message, cause);
    this.jobStatus = jobStatus;
    this.jsonError = jsonError;
  }

  /**
   * Returns a short error code describing why this job failed.
   *
   * <p>Sample Reasons:
   *
   * <ul>
   *   <li>{@code "duplicate"}: The table you're trying to create already exists.
   *   <li>{@code "invalidQuery"}: Query syntax error of some sort.
   *   <li>{@code "unknown"}: Non-Bigquery errors.
   * </ul>
   *
   * @see <a href="https://cloud.google.com/bigquery/troubleshooting-errors">Troubleshooting
   *     Errors</a>
   */
  public String getReason() {
    if (jobStatus != null) {
      return jobStatus.getErrorResult().getReason();
    } else if (jsonError != null) {
      return Iterables.getLast(jsonError.getErrors()).getReason();
    } else {
      return "unknown";
    }
  }

  @Override
  public String getMessage() {
    StringBuilder result = new StringBuilder();
    result.append(String.format("%s: %s", getClass().getSimpleName(), super.getMessage()));
    try {
      if (jobStatus != null) {
        for (ErrorProto error : jobStatus.getErrors()) {
          result.append("\n---------------------------------- BEGIN DEBUG INFO\n");
          result.append(describeError(error));
          result.append('\n');
          result.append(error.getDebugInfo());
          result.append("\n---------------------------------- END DEBUG INFO");
        }
      }
      if (jsonError != null) {
        String extraInfo = jsonError.toPrettyString();
        result.append('\n');
        result.append(extraInfo);
      }
    } catch (IOException e) {
      result.append(e);
    }
    return result.toString();
  }

  private static String describeError(ErrorProto error) {
    return String.format("%s: %s", error.getReason(), error.getMessage());
  }
}
