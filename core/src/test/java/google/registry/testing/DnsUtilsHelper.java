// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.testing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import google.registry.dns.DnsUtils;
import google.registry.model.annotations.DeleteAfterMigration;
import org.joda.time.Duration;

/**
 * Test helper for {@link DnsUtils}.
 *
 * <p>This is a temporary test class that is only used during DNS pull queue migration. Once we are
 * no longer using the pull queue method, we can just assert on the inserted SQL entry instead in
 * {@link DatabaseHelper}.
 */
@DeleteAfterMigration
public class DnsUtilsHelper {

  private final DnsUtils dnsUtils;

  public DnsUtilsHelper() {
    dnsUtils = mock(DnsUtils.class);
  }

  public DnsUtilsHelper(DnsUtils dnsUtils) {
    this.dnsUtils = dnsUtils;
  }

  public DnsUtils getDnsUtils() {
    return dnsUtils;
  }

  public void assertDomainDnsRequests(String... domainNames) {
    for (String domainName : domainNames) {
      verify(dnsUtils).requestDomainDnsRefresh(domainName);
    }
  }

  public void assertDomainDnsRequestWithDelay(String domainName, Duration delay) {
    verify(dnsUtils).requestDomainDnsRefresh(domainName, delay);
  }

  public void assertNoDomainDnsRequestWithDelay(String domainName, Duration delay) {
    verify(dnsUtils, never()).requestDomainDnsRefresh(domainName, delay);
  }

  public void assertHostDnsRequests(String... hostNames) {
    for (String hostName : hostNames) {
      verify(dnsUtils).requestHostDnsRefresh(hostName);
    }
  }

  public void assertNoDnsRequests() {
    verifyNoInteractions(dnsUtils);
  }
}
