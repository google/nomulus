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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.EppResource;
import java.util.Optional;
import org.joda.time.DateTime;

/** Abstract command to print one or more resources to stdout. */
@Parameters(separators = " =")
abstract class GetEppResourceCommand implements CommandWithRemoteApi {

  private final DateTime now = DateTime.now(UTC);

  @Parameter(
      names = "--read_timestamp",
      description = "Timestamp to use when reading. May not be in the past.")
  protected DateTime readTimestamp = now;

  @Parameter(
      names = "--expand",
      description = "Fully expand the requested resource. NOTE: Output may be lengthy.")
  boolean expand;

  /** Runs the command's own logic that calls {@link #printResource}. */
  abstract void runAndPrint();

  /**
   * Prints a possibly-absent resource to stdout, using resourceType and uniqueId to construct a
   * nice error message if the resource was null (i.e. doesn't exist).
   *
   * <p>The websafe key is appended to the output for use in e.g. manual mapreduce calls.
   */
  void printResource(
      String resourceType, String uniqueId, Optional<? extends EppResource> resource) {
    System.out.println(
        resource.isPresent()
            ? String.format(
                "%s\n\nWebsafe key: %s",
                expand ? resource.get().toHydratedString() : resource.get(),
                resource.get().createVKey().getOfyKey().getString())
            : String.format("%s '%s' does not exist or is deleted\n", resourceType, uniqueId));
  }

  @Override
  public void run() {
    checkArgument(!readTimestamp.isBefore(now), "--read_timestamp may not be in the past");
    runAndPrint();
  }
}
