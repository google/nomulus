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

import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableListMultimap;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;

/** Common unit test code for actions inheriting {@link RdapSearchActionBase}. */
public abstract class RdapSearchActionTestCase<A extends RdapSearchActionBase>
    extends RdapActionBaseTestCase<A> {

  RdapSearchActionTestCase(Class<A> rdapActionClass) {
    super(rdapActionClass);
  }

  SearchType metricSearchType = SearchType.NONE;
  WildcardType metricWildcardType = WildcardType.INVALID;
  int metricPrefixLength = 0;
  int metricStatusCode = SC_OK;

  @BeforeEach
  public void beforeEachRdapSearchActionTestCase() {
    action.parameterMap = ImmutableListMultimap.of();
    action.cursorTokenParam = Optional.empty();
    action.registrarParam = Optional.empty();
    action.rdapResultSetMaxSize = 4;
    action.requestUrl = "https://example.tld" + actionPath;
    action.requestPath = actionPath;
  }

  private void rememberWildcardType(WildcardType wildcardType, int prefixLength) {
    metricWildcardType = wildcardType;
    metricPrefixLength = prefixLength;
  }

  void rememberWildcardType(String searchQuery) {
    int wildcardLocation = searchQuery.indexOf('*');
    if (wildcardLocation < 0) {
      rememberWildcardType(WildcardType.NO_WILDCARD, searchQuery.length());
    } else if (wildcardLocation == searchQuery.length() - 1) {
      rememberWildcardType(WildcardType.PREFIX, wildcardLocation);
    } else if (wildcardLocation == 0) {
      rememberWildcardType(WildcardType.SUFFIX, wildcardLocation);
    } else {
      rememberWildcardType(WildcardType.PREFIX_AND_SUFFIX, wildcardLocation);
    }
  }

  void rememberWildcardTypeInvalid() {
    rememberWildcardType(WildcardType.INVALID, 0);
  }

  void verifyMetrics(
      EndpointType endpointType,
      Action.Method requestMethod,
      boolean includeDeleted,
      boolean registrarSpecified,
      Optional<Long> numDomainsRetrieved,
      Optional<Long> numHostsRetrieved,
      Optional<Long> numContactsRetrieved,
      IncompletenessWarningType incompletenessWarningType) {
    RdapMetrics.RdapMetricInformation.Builder builder =
        RdapMetrics.RdapMetricInformation.builder()
            .setEndpointType(endpointType)
            .setSearchType(metricSearchType)
            .setWildcardType(metricWildcardType)
            .setPrefixLength(metricPrefixLength)
            .setIncludeDeleted(includeDeleted)
            .setRegistrarSpecified(registrarSpecified)
            .setRole(metricRole)
            .setRequestMethod(requestMethod)
            .setStatusCode(metricStatusCode)
            .setIncompletenessWarningType(incompletenessWarningType);
    numDomainsRetrieved.ifPresent(builder::setNumDomainsRetrieved);
    numHostsRetrieved.ifPresent(builder::setNumHostsRetrieved);
    numContactsRetrieved.ifPresent(builder::setNumContactsRetrieved);
    verify(rdapMetrics).updateMetrics(builder.build());
  }
}
