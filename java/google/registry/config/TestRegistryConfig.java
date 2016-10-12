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

package google.registry.config;

import static google.registry.config.ConfigUtils.makeUrl;
import static org.joda.time.Duration.standardDays;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import java.net.URL;
import org.joda.time.Duration;

/**
 * An implementation of RegistryConfig for unit testing that contains suitable testing data.
 */
public class TestRegistryConfig implements RegistryConfig {

  public TestRegistryConfig() {}

  @Override
  public String getProjectId() {
    return "domain-registry";
  }

  @Override
  public int getCommitLogBucketCount() {
    return 1;
  }

  @Override
  public Duration getCommitLogDatastoreRetention() {
    return Duration.standardDays(30);
  }

  @Override
  public String getSnapshotsBucket() {
    return getProjectId() + "-snapshots";
  }

  @Override
  public String getDomainListsBucket() {
    return getProjectId() + "-domain-lists";
  }

  @Override
  public String getCommitsBucket() {
    return getProjectId() + "-commits";
  }

  @Override
  public String getZoneFilesBucket() {
    return getProjectId() + "-zonefiles";
  }

  @Override
  public boolean getTmchCaTestingMode() {
    return true;
  }

  @Override
  public String getTmchMarksdbUrl() {
    return "https://ry.marksdb.org";
  }

  @Override
  public Optional<String> getECatcherAddress() {
    throw new UnsupportedOperationException();
  }

  @Override
  public HostAndPort getServer() {
    return null;
  }

  @Override
  public Duration getSingletonCacheRefreshDuration() {
    // All cache durations are set to zero so that unit tests can update and then retrieve data
    // immediately without failure.
    return Duration.ZERO;
  }

  @Override
  public Duration getDomainLabelListCacheDuration() {
    return Duration.ZERO;
  }

  @Override
  public Duration getSingletonCachePersistDuration() {
    return Duration.ZERO;
  }

  @Override
  public String getReservedTermsExportDisclaimer() {
    return "This is a disclaimer.\n";
  }

  @Override
  public String getGoogleAppsAdminEmailDisplayName() {
    return "Testing Domain Registry";
  }

  @Override
  public String getGoogleAppsSendFromEmailAddress() {
    return "noreply@testing.example";
  }

  @Override
  public ImmutableList<String> getRegistrarChangesNotificationEmailAddresses() {
    return ImmutableList.of("notification@test.example", "notification2@test.example");
  }

  @Override
  public String getRegistrarDefaultWhoisServer() {
    return "whois.nic.fakewhois.example";
  }

  @Override
  public URL getRegistrarDefaultReferralUrl() {
    return makeUrl("http://www.referral.example/path");
  }

  @Override
  public String getDocumentationProjectTitle() {
    return "Nomulus";
  }

  @Override
  public int getMaxChecks() {
    return 50;
  }

  @Override
  public int getEppResourceIndexBucketCount() {
    return 2;
  }

  @Override
  public Duration getBaseOfyRetryDuration() {
    return Duration.ZERO;
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
    return "TheRegistrar";
  }
}
