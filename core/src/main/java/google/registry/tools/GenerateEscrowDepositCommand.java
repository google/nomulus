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

package google.registry.tools;

import static google.registry.model.tld.Tlds.assertTldsExist;
import static google.registry.rde.RdeModule.PARAM_DIRECTORY;
import static google.registry.rde.RdeModule.PARAM_LENIENT;
import static google.registry.rde.RdeModule.PARAM_MANUAL;
import static google.registry.rde.RdeModule.PARAM_MODE;
import static google.registry.rde.RdeModule.PARAM_REVISION;
import static google.registry.rde.RdeModule.PARAM_WATERMARKS;
import static google.registry.rde.RdeModule.RDE_REPORT_QUEUE;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_TLDS;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMultimap;
import google.registry.batch.CloudTasksUtils;
import google.registry.model.rde.RdeMode;
import google.registry.rde.RdeStagingAction;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Command to kick off the server-side generation of an XML RDE or BRDA escrow deposit, which will
 * be stored in the specified manual subdirectory of the GCS RDE bucket.
 */
@Parameters(separators = " =", commandDescription = "Generate an XML escrow deposit.")
final class GenerateEscrowDepositCommand implements Command {

  @Parameter(
      names = {"-t", "--tld"},
      description = "Top level domain(s) for which deposit should be generated.",
      required = true)
  private List<String> tlds;

  @Parameter(
      names = {"-w", "--watermark"},
      description = "Point-in-time timestamp(s) for which time the deposit should be generated",
      required = true)
  private List<DateTime> watermarks;

  @Parameter(
      names = {"-m", "--mode"},
      description = "Mode of operation: FULL for RDE deposits, THIN for BRDA deposits.")
  private RdeMode mode = RdeMode.FULL;

  @Parameter(
      names = {"-l", "--lenient"},
      description =
          "Whether to run RDE in LENIENT mode, which omits validation of the generated "
              + "XML deposit files.")
  private boolean lenient = false;

  @Parameter(
      names = {"-r", "--revision"},
      description = "Revision number. Use >0 for resends.")
  private Integer revision;

  @Parameter(
      names = {"-o", "--outdir"},
      description = "Specify output subdirectory (under GCS RDE bucket, directory manual).",
      required = true)
  private String outdir;

  @Inject CloudTasksUtils cloudTasksUtils;

  @Override
  public void run() {
    if (watermarks.isEmpty()) {
      throw new ParameterException("At least one watermark must be specified");
    }

    // We need to test for cases where "--watermark=" is passed in as a parameter, because it would
    // first be converted to an empty list, and as such the DateTime converter would not be called.
    if (tlds.isEmpty()) {
      throw new ParameterException("At least one TLD must be specified");
    }
    assertTldsExist(tlds);

    for (DateTime watermark : watermarks) {
      if (!watermark.withTimeAtStartOfDay().equals(watermark)) {
        throw new ParameterException("Each watermark date must be the start of a day");
      }
    }

    if ((revision != null) && (revision < 0)) {
      throw new ParameterException("Revision must be greater than or equal to zero");
    }

    if (outdir.isEmpty()) {
      throw new ParameterException("Output subdirectory must not be empty");
    }

    ImmutableMultimap.Builder<String, String> paramsBuilder =
        new ImmutableMultimap.Builder<String, String>()
            .put(PARAM_MANUAL, String.valueOf(true))
            .put(PARAM_MODE, mode.toString())
            .put(PARAM_DIRECTORY, outdir)
            .put(PARAM_LENIENT, Boolean.toString(lenient))
            .put(PARAM_TLDS, tlds.stream().collect(Collectors.joining(",")))
            .put(
                PARAM_WATERMARKS,
                watermarks.stream().map(DateTime::toString).collect(Collectors.joining(",")));

    if (revision != null) {
      paramsBuilder.put(PARAM_REVISION, String.valueOf(revision));
    }
    cloudTasksUtils.enqueue(
        RDE_REPORT_QUEUE,
        cloudTasksUtils.createTask(RdeStagingAction.class, POST, paramsBuilder.build()));
  }

}
