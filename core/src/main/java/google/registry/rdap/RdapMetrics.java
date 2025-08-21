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

package google.registry.rdap;

import com.google.auto.value.AutoBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.DistributionFitter;
import com.google.monitoring.metrics.EventMetric;
import com.google.monitoring.metrics.FibonacciFitter;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import jakarta.inject.Inject;
import java.util.Optional;

/** RDAP Instrumentation. */
public class RdapMetrics {

  enum EndpointType {
    AUTNUM,
    DOMAIN,
    DOMAINS,
    ENTITY,
    ENTITIES,
    HELP,
    IP,
    NAMESERVER,
    NAMESERVERS
  }

  enum SearchType {
    NONE,
    BY_DOMAIN_NAME,
    BY_NAMESERVER_NAME,
    BY_NAMESERVER_ADDRESS,
    BY_FULL_NAME,
    BY_HANDLE
  }

  enum WildcardType {
    NO_WILDCARD,
    PREFIX,
    SUFFIX,
    PREFIX_AND_SUFFIX,
    INVALID,
    TREATED_AS_WILDCARD
  }

  private static final int MAX_RECORDED_PREFIX_LENGTH = 5;
  private static final String MAX_PREFIX_LENGTH_LABEL = "5+";

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS_FOR_REQUESTS =
      ImmutableSet.of(
          LabelDescriptor.create("endpoint_type", "The RDAP endpoint."),
          LabelDescriptor.create("include_deleted", "Whether deleted records are included."),
          LabelDescriptor.create("registrar_specified", "Whether a registrar was specified"),
          LabelDescriptor.create("authorization", "Type of user authorization"),
          LabelDescriptor.create("httpMethod", "HTTP request method"));

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS_FOR_RESPONSES =
      ImmutableSet.of(
          LabelDescriptor.create("endpoint_type", "The RDAP endpoint."),
          LabelDescriptor.create("search_type", "The identifier type used to search."),
          LabelDescriptor.create("wildcard_type", "The search string wildcard type."),
          LabelDescriptor.create("status_code", "Returned HTTP status code"),
          LabelDescriptor.create(
              "incompleteness_warning_type",
              "Warning status returned with result set (e.g. truncated, incomplete"));

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS_FOR_RETRIEVAL_COUNTS =
      ImmutableSet.of(
          LabelDescriptor.create("endpoint_type", "The RDAP endpoint."),
          LabelDescriptor.create("search_type", "The identifier type used to search."),
          LabelDescriptor.create("wildcard_type", "The search string wildcard type."),
          LabelDescriptor.create(
              "prefix_length",
              String.format(
                  "The length of the prefix before the wildcard (limited to %d).",
                  MAX_RECORDED_PREFIX_LENGTH)),
          LabelDescriptor.create("include_deleted", "Whether deleted records are included."));

  // Fibonacci fitter more suitable for integer-type values. Allows values between 0 and 4181,
  // which is the 19th Fibonacci number.
  private static final DistributionFitter FIBONACCI_FITTER = FibonacciFitter.create(4181);

  @VisibleForTesting
  static final IncrementableMetric requests =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/rdap/requests", "Count of RDAP Requests", "count", LABEL_DESCRIPTORS_FOR_REQUESTS);

  @VisibleForTesting
  static final IncrementableMetric responses =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/rdap/responses",
              "Count of RDAP Responses",
              "count",
              LABEL_DESCRIPTORS_FOR_RESPONSES);

  @VisibleForTesting
  static final EventMetric numberOfDomainsRetrieved =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/rdap/num_domains_retrieved",
              "Number of domains retrieved",
              "count",
              LABEL_DESCRIPTORS_FOR_RETRIEVAL_COUNTS,
              FIBONACCI_FITTER);

  @VisibleForTesting
  static final EventMetric numberOfHostsRetrieved =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/rdap/num_hosts_retrieved",
              "Number of hosts retrieved",
              "count",
              LABEL_DESCRIPTORS_FOR_RETRIEVAL_COUNTS,
              FIBONACCI_FITTER);

  @Inject
  public RdapMetrics() {}

  private static String getLabelStringForPrefixLength(int prefixLength) {
    return (prefixLength >= MAX_RECORDED_PREFIX_LENGTH)
        ? MAX_PREFIX_LENGTH_LABEL
        : String.valueOf(prefixLength);
  }

  /**
   * Increments the RDAP metrics.
   *
   * <p>This is intended to be called at the conclusion of a query, with the parameters specifying
   * everything that happened. This method takes the data and updates metrics which offer several
   * ways of looking at the data, since cardinality constraints prevent us from saving all the
   * information in a single metric.
   */
  public void updateMetrics(RdapMetricInformation rdapMetricInformation) {
    requests.increment(
        rdapMetricInformation.endpointType().toString(),
        rdapMetricInformation.includeDeleted() ? "YES" : "NO",
        rdapMetricInformation.registrarSpecified() ? "YES" : "NO",
        rdapMetricInformation.role().toString(),
        rdapMetricInformation.requestMethod().toString());
    responses.increment(
        rdapMetricInformation.endpointType().toString(),
        rdapMetricInformation.searchType().toString(),
        rdapMetricInformation.wildcardType().toString(),
        String.valueOf(rdapMetricInformation.statusCode()),
        rdapMetricInformation.incompletenessWarningType().toString());
    if (rdapMetricInformation.numDomainsRetrieved().isPresent()) {
      numberOfDomainsRetrieved.record(
          rdapMetricInformation.numDomainsRetrieved().get(),
          rdapMetricInformation.endpointType().toString(),
          rdapMetricInformation.searchType().toString(),
          rdapMetricInformation.wildcardType().toString(),
          getLabelStringForPrefixLength(rdapMetricInformation.prefixLength()),
          rdapMetricInformation.includeDeleted() ? "YES" : "NO");
    }
    if (rdapMetricInformation.numHostsRetrieved().isPresent()) {
      numberOfHostsRetrieved.record(
          rdapMetricInformation.numHostsRetrieved().get(),
          rdapMetricInformation.endpointType().toString(),
          rdapMetricInformation.searchType().toString(),
          rdapMetricInformation.wildcardType().toString(),
          getLabelStringForPrefixLength(rdapMetricInformation.prefixLength()),
          rdapMetricInformation.includeDeleted() ? "YES" : "NO");
    }
  }

  /**
   * Information on RDAP metrics.
   *
   * @param endpointType The type of RDAP endpoint (domain, domains, nameserver, etc.).
   * @param searchType The search type (by domain name, by nameserver name, etc.).
   * @param wildcardType The type of wildcarding requested (prefix, suffix, etc.).
   * @param prefixLength The length of the prefix string before the wildcard, if any; any length
   *     longer than MAX_RECORDED_PREFIX_LENGTH is limited to MAX_RECORDED_PREFIX_LENGTH when
   *     recording the metric, to avoid cardinality problems.
   * @param includeDeleted Whether the search included deleted records.
   * @param registrarSpecified Whether the search requested a specific registrar.
   * @param role Type of authentication/authorization: public, admin or registrar.
   * @param requestMethod Http request method (GET, POST, HEAD, etc.).
   * @param statusCode Http status code.
   * @param incompletenessWarningType Incompleteness warning type (e.g. truncated).
   * @param numDomainsRetrieved Number of domains retrieved from the database; this might be more
   *     than were actually returned in the response; absent if a search was not performed.
   * @param numHostsRetrieved Number of hosts retrieved from the database; this might be more than
   *     were actually returned in the response; absent if a search was not performed.
   */
  public record RdapMetricInformation(
      EndpointType endpointType,
      SearchType searchType,
      WildcardType wildcardType,
      int prefixLength,
      boolean includeDeleted,
      boolean registrarSpecified,
      RdapAuthorization.Role role,
      Action.Method requestMethod,
      int statusCode,
      IncompletenessWarningType incompletenessWarningType,
      Optional<Long> numDomainsRetrieved,
      Optional<Long> numHostsRetrieved) {

    @AutoBuilder
    interface Builder {
      Builder setEndpointType(EndpointType endpointType);

      Builder setSearchType(SearchType searchType);

      Builder setWildcardType(WildcardType wildcardType);

      Builder setPrefixLength(int prefixLength);

      Builder setIncludeDeleted(boolean includeDeleted);

      Builder setRegistrarSpecified(boolean registrarSpecified);

      Builder setRole(RdapAuthorization.Role role);

      Builder setRequestMethod(Action.Method requestMethod);

      Builder setStatusCode(int statusCode);

      Builder setIncompletenessWarningType(IncompletenessWarningType incompletenessWarningType);

      Builder setNumDomainsRetrieved(long numDomainsRetrieved);

      Builder setNumHostsRetrieved(long numHostsRetrieved);

      RdapMetricInformation build();
    }

    static Builder builder() {
      return new AutoBuilder_RdapMetrics_RdapMetricInformation_Builder()
          .setSearchType(SearchType.NONE)
          .setWildcardType(WildcardType.INVALID)
          .setPrefixLength(0)
          .setRegistrarSpecified(false)
          .setIncompletenessWarningType(IncompletenessWarningType.COMPLETE);
    }
  }
}
