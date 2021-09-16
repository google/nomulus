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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Collections.EMPTY_LIST;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.flows.FlowModule.InputXml;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import java.util.Optional;
import javax.inject.Inject;
import org.json.simple.JSONValue;

/** Reporter used by {@link FlowRunner} to record flow execution data for reporting. */
public class FlowReporter {

  /**
   * Log signature for recording flow metadata (anything beyond or derived from the raw EPP input).
   *
   * <p><b>WARNING:<b/> DO NOT CHANGE this value unless you want to break reporting.
   */
  private static final String METADATA_LOG_SIGNATURE = "FLOW-LOG-SIGNATURE-METADATA";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Trid trid;
  @Inject @RegistrarId String registrarId;
  @Inject @InputXml byte[] inputXmlBytes;
  @Inject EppInput eppInput;
  @Inject Class<? extends Flow> flowClass;
  @Inject FlowReporter() {}

  /** Records information about the current flow execution in the GAE request logs. */
  public void recordToLogs() {
    // Explicitly log flow metadata separately from the EPP XML itself so that it stays compact
    // enough to be sure to fit in a single log entry (the XML part in rare cases could be long
    // enough to overflow into multiple log entries, breaking routine parsing of the JSON format).
    String singleTargetId = eppInput.getSingleTargetId().orElse("");
    ImmutableList<String> targetIds = eppInput.getTargetIds();
    logger.atInfo().log(
        "%s: %s",
        METADATA_LOG_SIGNATURE,
        JSONValue.toJSONString(
            new ImmutableMap.Builder<String, Object>()
                .put("serverTrid", trid.getServerTransactionId())
                .put("clientId", registrarId)
                .put("commandType", eppInput.getCommandType())
                .put("resourceType", eppInput.getResourceType().orElse(""))
                .put("flowClassName", flowClass.getSimpleName())
                .put("targetId", singleTargetId)
                .put("targetIds", targetIds)
                .put("tld", eppInput.isDomainType() ? extractTld(singleTargetId).orElse("") : "")
                .put("tlds", eppInput.isDomainType() ? extractTlds(targetIds).asList() : EMPTY_LIST)
                .put("icannActivityReportField", extractActivityReportField(flowClass))
                .build()));
  }

  /**
   * Returns the guessed TLD of the given domain name, assuming a second-level domain name, or
   * absent if no TLD could be detected.
   *
   * <p>This method is quick and dirty and doesn't attempt to validate the domain name in any way;
   * it just takes anything after the first period to be the TLD and converts ASCII to lowercase.
   * We want quick and dirty here because this will be called on not-yet-validated EPP XML where
   * just about anything could be supplied, and there's no reason to validate twice when this just
   * needs to be roughly correct.
   */
  private static Optional<String> extractTld(String domainName) {
    int index = domainName.indexOf('.');
    return index == -1
        ? Optional.empty()
        : Optional.of(Ascii.toLowerCase(domainName.substring(index + 1)));
  }

  /**
   * Returns the set of unique results of {@link #extractTld} applied to each given domain name,
   * excluding any absent results (i.e. cases where no TLD was detected).
   */
  public static ImmutableSet<String> extractTlds(Iterable<String> domainNames) {
    return Streams.stream(domainNames)
        .map(FlowReporter::extractTld)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(toImmutableSet());
  }

  /**
   * Returns the ICANN activity report field for the given flow class, or the empty string if no
   * activity report field specification is found.
   */
  private static String extractActivityReportField(Class<? extends Flow> flowClass) {
    ReportingSpec reportingSpec = flowClass.getAnnotation(ReportingSpec.class);
    if (reportingSpec != null) {
      return reportingSpec.value().getFieldName();
    }
    return "";
  }
}
