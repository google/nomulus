// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package domains.donuts.config;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.config.ConfigUtils.makeUrl;
import static org.joda.time.Duration.standardDays;

import com.google.appengine.api.utils.SystemProperty;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryEnvironment;
import java.net.URL;
import javax.annotation.concurrent.Immutable;
import org.joda.time.Duration;

/** Donuts production {@link RegistryConfig}. */
@Immutable
public final class DonutsRegistryConfig implements RegistryConfig {

  private final RegistryEnvironment environment;

  private static final String RESERVED_TERMS_EXPORT_DISCLAIMER = ""
      + "# This list contains reserve terms for the TLD. Other terms may be reserved\n"
      + "# but not included in this list, including terms Donuts Inc. chooses not\n"
      + "# to publish, and terms that ICANN commonly mandates to be reserved. This\n"
      + "# list is subject to change and the most up-to-date source is always to\n"
      + "# check availability directly with the Registry server.\n";

  public DonutsRegistryConfig(RegistryEnvironment environment) {
    this.environment = checkNotNull(environment);
  }

  @Override
  public String getProjectId() {
    // TODO: Create App Engine projects named mercury-donuts-alpha, mercury-donuts-sandbox, etc.
    switch (environment) {
      case PRODUCTION:
        return "mercury-donuts";
      default:
        return "mercury-donuts-" + Ascii.toLowerCase(environment.name());
    }
  }

  @Override
  public int getCommitLogBucketCount() {
    // TODO: May cause NPE (for one request) in ExportCommitLogDiffAction until merging Google fix to CommitLogCheckpoint.
    //       See: https://github.com/DonutsInc/domain-registry/pull/21#issuecomment-238730780
    return 100;  // if you decrease this number, the world ends
  }

  /**
   * {@inheritDoc}
   *
   * <p>Thirty days makes a sane default, because it's highly unlikely we'll ever need to generate a
   * deposit older than that. And if we do, we could always bring up a separate App Engine instance
   * and replay the commit logs off GCS.
   */
  @Override
  public Duration getCommitLogDatastoreRetention() {
    return Duration.standardDays(30);
  }

  @Override
  public String getSnapshotsBucket() {
    return getProjectId() + "-snapshots";
  }

  @Override
  public boolean getTmchCaTestingMode() {
    switch (environment) {
      case PRODUCTION:
        return false;
      default:
        return true;
    }
  }

  @Override
  public Optional<String> getECatcherAddress() {
    throw new UnsupportedOperationException();  // n/a
  }

  @Override
  public HostAndPort getServer() {
    switch (environment) {
      case LOCAL:
        return HostAndPort.fromParts("localhost", 8080);
      default:
        String host = Joiner.on(".").join("tools", getProjectId(), "appspot.com");
        return HostAndPort.fromParts(host, 443);
    }
  }

  @Override
  public Duration getSingletonCacheRefreshDuration() {
    return Duration.standardMinutes(10);
  }

  @Override
  public Duration getDomainLabelListCacheDuration() {
    return Duration.standardHours(1);
  }

  @Override
  public Duration getSingletonCachePersistDuration() {
    return Duration.standardDays(365);
  }

  @Override
  public String getReservedTermsExportDisclaimer() {
    return RESERVED_TERMS_EXPORT_DISCLAIMER;
  }

  @Override
  public String getGoogleAppsAdminEmailDisplayName() {
    return "Donuts Registry";
  }

  @Override
  public String getGoogleAppsSendFromEmailAddress() {
    return String.format("noreply@%s.appspotmail.com", SystemProperty.applicationId.get());
  }

  @Override
  public String getRegistrarDefaultWhoisServer() {
    return "whois.donuts.co";
  }

  @Override
  public URL getRegistrarDefaultReferralUrl() {
    return makeUrl("https://www.donuts.domains");
  }

  @Override
  public String getDocumentationProjectTitle() {
    return "Donuts Registry";
  }

  @Override
  public int getEppResourceIndexBucketCount() {
    return 997;  // decrease this and the world ends
  }

  @Override
  public Duration getBaseOfyRetryDuration() {
    return Duration.millis(100);
  }

  @Override
  public String getContactAndHostRepositoryIdentifier() {
    return "ROID";
  }

  @Override
  public Duration getContactAutomaticTransferLength() {
    return standardDays(5);
  }

  @Override
  public String getCheckApiServletRegistrarClientId() {
    // TODO: Create a Registrar in datastore called "Donuts." It's used to buy domains from
    //       yourself, among other things.
    return "Donuts";
  }

}
