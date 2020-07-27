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

import static com.google.monitoring.metrics.contrib.DistributionMetricSubject.assertThat;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;

import com.google.common.collect.ImmutableSet;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapMetrics}. */
class RdapMetricsTest {

  private final RdapMetrics rdapMetrics = new RdapMetrics();

  @BeforeEach
  void beforeEach() {
    RdapMetrics.requests.reset();
    RdapMetrics.responses.reset();
    RdapMetrics.numberOfDomainsRetrieved.reset();
    RdapMetrics.numberOfHostsRetrieved.reset();
    RdapMetrics.numberOfContactsRetrieved.reset();
  }

  private RdapMetrics.RdapMetricInformation.Builder getBuilder() {
    return RdapMetrics.RdapMetricInformation.builder()
        .setEndpointType(EndpointType.DOMAINS)
        .setSearchType(SearchType.NONE)
        .setWildcardType(WildcardType.INVALID)
        .setPrefixLength(0)
        .setIncludeDeleted(false)
        .setRegistrarSpecified(false)
        .setRole(RdapAuthorization.Role.PUBLIC)
        .setRequestMethod(Action.Method.GET)
        .setStatusCode(200)
        .setIncompletenessWarningType(IncompletenessWarningType.COMPLETE);
  }

  @Test
  void testPost() {
    rdapMetrics.updateMetrics(getBuilder().setRequestMethod(Action.Method.POST).build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "DOMAINS", "NO", "NO", "PUBLIC", "POST")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testHead() {
    rdapMetrics.updateMetrics(getBuilder().setRequestMethod(Action.Method.HEAD).build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "DOMAINS", "NO", "NO", "PUBLIC", "HEAD")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testPrefixLength_cappedAt5() {
    rdapMetrics.updateMetrics(
        getBuilder().setPrefixLength(6).setNumDomainsRetrieved(1).build());
    assertThat(RdapMetrics.numberOfDomainsRetrieved)
        .hasDataSetForLabels(ImmutableSet.of(1), "DOMAINS", "NONE", "INVALID", "5+", "NO")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testIncludeDeleted() {
    rdapMetrics.updateMetrics(getBuilder().setIncludeDeleted(true).build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "DOMAINS", "YES", "NO", "PUBLIC", "GET")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testDesiredRegistrar() {
    rdapMetrics.updateMetrics(getBuilder().setRegistrarSpecified(true).build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "DOMAINS", "NO", "YES", "PUBLIC", "GET")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testCompleteResultSet() {
    rdapMetrics.updateMetrics(
        getBuilder()
            .setIncompletenessWarningType(IncompletenessWarningType.COMPLETE)
            .build());
    assertThat(RdapMetrics.responses)
        .hasValueForLabels(1, "DOMAINS", "NONE", "INVALID", "200", "COMPLETE")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testTruncatedResultSet() {
    rdapMetrics.updateMetrics(
        getBuilder()
            .setIncompletenessWarningType(IncompletenessWarningType.TRUNCATED)
            .build());
    assertThat(RdapMetrics.responses)
        .hasValueForLabels(1, "DOMAINS", "NONE", "INVALID", "200", "TRUNCATED")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testPossiblyIncompleteResultSet() {
    rdapMetrics.updateMetrics(
        getBuilder()
            .setIncompletenessWarningType(IncompletenessWarningType.MIGHT_BE_INCOMPLETE)
            .build());
    assertThat(RdapMetrics.responses)
        .hasValueForLabels(1, "DOMAINS", "NONE", "INVALID", "200", "MIGHT_BE_INCOMPLETE")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testPublicRole() {
    rdapMetrics.updateMetrics(getBuilder().setRole(RdapAuthorization.Role.PUBLIC).build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "DOMAINS", "NO", "NO", "PUBLIC", "GET")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testRegistrarRole() {
    rdapMetrics.updateMetrics(getBuilder().setRole(RdapAuthorization.Role.REGISTRAR).build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "DOMAINS", "NO", "NO", "REGISTRAR", "GET")
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testAdminRole() {
    rdapMetrics.updateMetrics(getBuilder().setRole(RdapAuthorization.Role.ADMINISTRATOR).build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "DOMAINS", "NO", "NO", "ADMINISTRATOR", "GET")
        .and()
        .hasNoOtherValues();
  }

  /** Tests what would happen in a domain search for "cat.lol" which found that domain. */
  @Test
  void testSimpleDomainSearch() {
    rdapMetrics.updateMetrics(
        getBuilder()
            .setSearchType(SearchType.BY_DOMAIN_NAME)
            .setWildcardType(WildcardType.NO_WILDCARD)
            .setPrefixLength(7)
            .setNumDomainsRetrieved(1)
            .build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "DOMAINS", "NO", "NO", "PUBLIC", "GET")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.responses)
        .hasValueForLabels(1, "DOMAINS", "BY_DOMAIN_NAME", "NO_WILDCARD", "200", "COMPLETE")
        .and()
        .hasNoOtherValues();
    // The prefix length is capped at 5.
    assertThat(RdapMetrics.numberOfDomainsRetrieved)
        .hasDataSetForLabels(
            ImmutableSet.of(1), "DOMAINS", "BY_DOMAIN_NAME", "NO_WILDCARD", "5+", "NO")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.numberOfHostsRetrieved).hasNoOtherValues();
    assertThat(RdapMetrics.numberOfContactsRetrieved).hasNoOtherValues();
  }

  /**
   * Tests what would happen in a domain search by nameserver name for "ns*.cat.lol", including
   * deleted domains, which found 10 matching hosts, then looked for domains and found 5 matches.
   */
  @Test
  void testDomainSearchByNameserverWithWildcardAndDeleted() {
    rdapMetrics.updateMetrics(
        getBuilder()
            .setSearchType(SearchType.BY_NAMESERVER_NAME)
            .setWildcardType(WildcardType.PREFIX_AND_SUFFIX)
            .setPrefixLength(2)
            .setIncludeDeleted(true)
            .setNumDomainsRetrieved(5)
            .setNumHostsRetrieved(10)
            .build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "DOMAINS", "YES", "NO", "PUBLIC", "GET")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.responses)
        .hasValueForLabels(
            1, "DOMAINS", "BY_NAMESERVER_NAME", "PREFIX_AND_SUFFIX", "200", "COMPLETE")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.numberOfDomainsRetrieved)
        .hasDataSetForLabels(
            ImmutableSet.of(5), "DOMAINS", "BY_NAMESERVER_NAME", "PREFIX_AND_SUFFIX", "2", "YES")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.numberOfHostsRetrieved)
        .hasDataSetForLabels(
            ImmutableSet.of(10), "DOMAINS", "BY_NAMESERVER_NAME", "PREFIX_AND_SUFFIX", "2", "YES")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.numberOfContactsRetrieved).hasNoOtherValues();
  }

  /** Tests what would happen in a nameserver search for "*.cat.lol", which found no matches. */
  @Test
  void testNoNameserversFound() {
    rdapMetrics.updateMetrics(
        getBuilder()
            .setEndpointType(EndpointType.NAMESERVERS)
            .setSearchType(SearchType.BY_NAMESERVER_NAME)
            .setWildcardType(WildcardType.SUFFIX)
            .setStatusCode(404)
            .setNumHostsRetrieved(0)
            .build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "NAMESERVERS", "NO", "NO", "PUBLIC", "GET")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.responses)
        .hasValueForLabels(
            1, "NAMESERVERS", "BY_NAMESERVER_NAME", "SUFFIX", "404", "COMPLETE")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.numberOfDomainsRetrieved).hasNoOtherValues();
    assertThat(RdapMetrics.numberOfHostsRetrieved)
    .hasDataSetForLabels(
        ImmutableSet.of(0), "NAMESERVERS", "BY_NAMESERVER_NAME", "SUFFIX", "0", "NO")
    .and()
    .hasNoOtherValues();
    assertThat(RdapMetrics.numberOfContactsRetrieved).hasNoOtherValues();
  }

  /** Tests what would happen in an entity search for "Mike*" which found 50 contacts. */
  @Test
  void testEntitySearchByNameWithWildcard() {
    rdapMetrics.updateMetrics(
        getBuilder()
            .setEndpointType(EndpointType.ENTITIES)
            .setSearchType(SearchType.BY_FULL_NAME)
            .setWildcardType(WildcardType.PREFIX)
            .setPrefixLength(4)
            .setNumContactsRetrieved(50)
            .build());
    assertThat(RdapMetrics.requests)
        .hasValueForLabels(1, "ENTITIES", "NO", "NO", "PUBLIC", "GET")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.responses)
        .hasValueForLabels(
            1, "ENTITIES", "BY_FULL_NAME", "PREFIX", "200", "COMPLETE")
        .and()
        .hasNoOtherValues();
    assertThat(RdapMetrics.numberOfDomainsRetrieved).hasNoOtherValues();
    assertThat(RdapMetrics.numberOfHostsRetrieved).hasNoOtherValues();
    assertThat(RdapMetrics.numberOfContactsRetrieved)
        .hasDataSetForLabels(
            ImmutableSet.of(50), "ENTITIES", "BY_FULL_NAME", "PREFIX", "4", "NO")
        .and()
        .hasNoOtherValues();
  }
}
