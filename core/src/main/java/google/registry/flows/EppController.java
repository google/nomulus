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

package google.registry.flows;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.flogger.LazyArgs.lazy;
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.flows.FlowReporter.extractTlds;
import static google.registry.flows.FlowUtils.unmarshalEpp;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.flows.FlowModule.EppExceptionInProviderException;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.Result;
import google.registry.model.eppoutput.Result.Code;
import google.registry.monitoring.whitebox.EppMetric;
import java.util.Optional;
import javax.inject.Inject;
import org.json.simple.JSONValue;

/**
 * An implementation of the EPP command/response protocol.
 *
 * @see <a href="http://tools.ietf.org/html/rfc5730">RFC 5730 - Extensible Provisioning Protocol</a>
 */
public final class EppController {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String LOG_SEPARATOR = Strings.repeat("=", 40);

  @Inject FlowComponent.Builder flowComponentBuilder;
  @Inject EppMetric.Builder eppMetricBuilder;
  @Inject EppMetrics eppMetrics;
  @Inject ServerTridProvider serverTridProvider;
  @Inject EppController() {}

  /** Reads EPP XML, executes the matching flow, and returns an {@link EppOutput}. */
  public EppOutput handleEppCommand(
      SessionMetadata sessionMetadata,
      TransportCredentials credentials,
      EppRequestSource eppRequestSource,
      boolean isDryRun,
      boolean isSuperuser,
      byte[] inputXmlBytes) {
    eppMetricBuilder.setRegistrarId(Optional.ofNullable(sessionMetadata.getRegistrarId()));
    try {
      EppInput eppInput;
      try {
        eppInput = unmarshalEpp(EppInput.class, inputXmlBytes);
      } catch (EppException e) {
        // Log the unmarshalling error, with the raw bytes (in base64) to help with debugging.
        logger.atInfo().withCause(e).log(
            "EPP request XML unmarshalling failed - \"%s\":\n%s\n%s\n%s\n%s",
            e.getMessage(),
            lazy(
                () ->
                    JSONValue.toJSONString(
                        ImmutableMap.<String, Object>of(
                            "clientId",
                            nullToEmpty(sessionMetadata.getRegistrarId()),
                            "resultCode",
                            e.getResult().getCode().code,
                            "resultMessage",
                            e.getResult().getCode().msg,
                            "xmlBytes",
                            base64().encode(inputXmlBytes)))),
            LOG_SEPARATOR,
            lazy(
                () ->
                    new String(inputXmlBytes, UTF_8)
                        .trim()), // Charset decoding failures are swallowed.
            LOG_SEPARATOR);
        // Return early by sending an error message, with no clTRID since we couldn't unmarshal it.
        eppMetricBuilder.setStatus(e.getResult().getCode());
        return getErrorResponse(
            e.getResult(), Trid.create(null, serverTridProvider.createServerTrid()));
      }
      if (!eppInput.getTargetIds().isEmpty()) {
        if (eppInput.isDomainType()) {
          eppMetricBuilder.setTlds(extractTlds(eppInput.getTargetIds()));
        }
      }
      EppOutput output = runFlowConvertEppErrors(flowComponentBuilder
          .flowModule(new FlowModule.Builder()
              .setSessionMetadata(sessionMetadata)
              .setCredentials(credentials)
              .setEppRequestSource(eppRequestSource)
              .setIsDryRun(isDryRun)
              .setIsSuperuser(isSuperuser)
              .setInputXmlBytes(inputXmlBytes)
              .setEppInput(eppInput)
              .build())
          .build());
      if (output.isResponse()) {
        eppMetricBuilder.setStatus(output.getResponse().getResult().getCode());
      }
      return output;
    } finally {
      if (!isDryRun) {
        EppMetric metric = eppMetricBuilder.build();
        eppMetrics.incrementEppRequests(metric);
        eppMetrics.recordProcessingTime(metric);
      }
    }
  }

  /** Runs an EPP flow and converts known exceptions into EPP error responses. */
  private EppOutput runFlowConvertEppErrors(FlowComponent flowComponent) {
    try {
      return flowComponent.flowRunner().run(eppMetricBuilder);
    } catch (EppException | EppExceptionInProviderException e) {
      // The command failed. Send the client an error message, but only log at INFO since many of
      // these failures are innocuous or due to client error, so there's nothing we have to change.
      logger.atInfo().withCause(e).log("Flow returned failure response.");
      EppException eppEx = (EppException) (e instanceof EppException ? e : e.getCause());
      return getErrorResponse(eppEx.getResult(), flowComponent.trid());
    } catch (Throwable e) {
      // Something bad and unexpected happened. Send the client a generic error, and log at SEVERE.
      logger.atSevere().withCause(e).log("Unexpected failure in flow execution.");
      return getErrorResponse(Result.create(Code.COMMAND_FAILED), flowComponent.trid());
    }
  }

  /** Creates a response indicating an EPP failure. */
  @VisibleForTesting
  static EppOutput getErrorResponse(Result result, Trid trid) {
    return EppOutput.create(new EppResponse.Builder().setResult(result).setTrid(trid).build());
  }
}
