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

package google.registry.dns.writer;

import com.google.common.flogger.FluentLogger;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;

/**
 * {@link DnsWriter} that doesn't actually update records in a DNS server.
 *
 * <p>All this class does is write its displeasure to the logs.
 */
public final class VoidDnsWriter extends BaseDnsWriter {

  /**
   * The name of the pricing engine, as used in {@code Registry.dnsWriter}. Remember to change
   * the value on affected Registry objects to prevent runtime failures.
   */
  public static final String NAME = "VoidDnsWriter";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Set<String> names = new HashSet<>();

  @Inject
  public VoidDnsWriter() {}

  @Override
  public void publishDomain(String domainName) {
    names.add(domainName);
  }

  @Override
  public void publishHost(String hostName) {
    names.add(hostName);
  }

  @Override
  protected void commitUnchecked() {
    logger.atWarning().log(
        "No DnsWriterFactory implementation specified; ignoring names to commit: %s", names);
  }
}
