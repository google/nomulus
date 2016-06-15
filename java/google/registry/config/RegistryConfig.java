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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;

import org.joda.time.Duration;

import java.net.URL;

/**
 * Domain Registry configuration.
 *
 * <p>The goal of this custom configuration system is to have our project environments configured
 * in type-safe Java code that can be refactored, rather than XML files and system properties.
 *
 * <p><b>Note:</b> This interface is deprecated by {@link ConfigModule}.
 */
public interface RegistryConfig {

  /**
   * Returns the App Engine project ID, which is based off the environment name.
   */
  public String getProjectId();

  /**
   * Returns the Google Cloud Storage bucket for storing backup snapshots.
   *
   * @see google.registry.export.ExportSnapshotServlet
   */
  public String getSnapshotsBucket();

  /**
   * Returns the Google Cloud Storage bucket for storing exported domain lists.
   *
   * @see google.registry.export.ExportDomainListsAction
   */
  public String getDomainListsBucket();

  /**
   * Number of sharded commit log buckets.
   *
   * <p>This number is crucial for determining how much transactional throughput the system can
   * allow, because it determines how many entity groups are available for writing commit logs.
   * Since entity groups have a one transaction per second SLA (which is actually like ten in
   * practice), a registry that wants to be able to handle one hundred transactions per second
   * should have one hundred buckets.
   *
   * <p><b>Warning:</b> This can be raised but never lowered.
   *
   * @see google.registry.model.ofy.CommitLogBucket
   */
  public int getCommitLogBucketCount();

  /**
   * Returns the length of time before commit logs should be deleted from datastore.
   *
   * <p>The only reason you'll want to retain this commit logs in datastore is for performing
   * point-in-time restoration queries for subsystems like RDE.
   *
   * @see google.registry.backup.DeleteOldCommitLogsAction
   * @see google.registry.model.translators.CommitLogRevisionsTranslatorFactory
   */
  public Duration getCommitLogDatastoreRetention();

  /**
   * Returns the Google Cloud Storage bucket for storing commit logs.
   *
   * @see google.registry.backup.ExportCommitLogDiffAction
   */
  public String getCommitsBucket();

  /**
   * Returns the Google Cloud Storage bucket for storing zone files.
   *
   * @see google.registry.backup.ExportCommitLogDiffAction
   */
  public String getZoneFilesBucket();

  /**
   * Returns {@code true} if TMCH certificate authority should be in testing mode.
   *
   * @see google.registry.tmch.TmchCertificateAuthority
   */
  public boolean getTmchCaTestingMode();

  /**
   * URL prefix for communicating with MarksDB ry interface.
   *
   * <p>This URL is used for DNL, SMDRL, and LORDN.
   *
   * @see google.registry.tmch.Marksdb
   * @see google.registry.tmch.NordnUploadAction
   */
  public String getTmchMarksdbUrl();

  public Optional<String> getECatcherAddress();

  /**
   * Returns the address of the Domain Registry app HTTP server.
   *
   * <p>This is used by {@code registry_tool} to connect to the App Engine remote API.
   */
  public HostAndPort getServer();

  /** Returns the amount of time a singleton should be cached, before expiring. */
  public Duration getSingletonCacheRefreshDuration();

  /**
   * Returns the amount of time a domain label list should be cached in memory before expiring.
   *
   * @see google.registry.model.registry.label.ReservedList
   * @see google.registry.model.registry.label.PremiumList
   */
  public Duration getDomainLabelListCacheDuration();

  /** Returns the amount of time a singleton should be cached in persist mode, before expiring. */
  public Duration getSingletonCachePersistDuration();

  /**
   * Returns the header text at the top of the reserved terms exported list.
   *
   * @see google.registry.export.ExportUtils#exportReservedTerms
   */
  public String getReservedTermsExportDisclaimer();

  /**
   * Returns a display name that is used on outgoing emails sent by Domain Registry.
   *
   * @see google.registry.util.SendEmailUtils
   */
  public String getGoogleAppsAdminEmailDisplayName();

  /**
   * Returns the email address that outgoing emails from the app are sent from.
   *
   * @see google.registry.util.SendEmailUtils
   */
  public String getGoogleAppsSendFromEmailAddress();

  /**
   * Returns the roid suffix to be used for the roids of all contacts and hosts.  E.g. a value of
   * "ROID" would end up creating roids that look like "ABC123-ROID".
   *
   * @see <a href="http://www.iana.org/assignments/epp-repository-ids/epp-repository-ids.xhtml">
   *      Extensible Provisioning Protocol (EPP) Repository Identifiers</a>
   */
  public String getContactAndHostRepositoryIdentifier();

  /**
   * Returns the email address(es) that notifications of registrar and/or registrar contact updates
   * should be sent to, or the empty list if updates should not be sent.
   *
   * @see google.registry.ui.server.registrar.RegistrarServlet
   */
  public ImmutableList<String> getRegistrarChangesNotificationEmailAddresses();

  /**
   * Returns default WHOIS server to use when {@code Registrar#getWhoisServer()} is {@code null}.
   *
   * @see "google.registry.whois.DomainWhoisResponse"
   * @see "google.registry.whois.RegistrarWhoisResponse"
   */
  public String getRegistrarDefaultWhoisServer();

  /**
   * Returns the default referral URL that is used unless registrars have specified otherwise.
   */
  public URL getRegistrarDefaultReferralUrl();

  /**
   * Returns the title of the project used in generating documentation.
   */
  public String getDocumentationProjectTitle();

  /**
   * Returns the maximum number of entities that can be checked at one time in an EPP check flow.
   */
  public int getMaxChecks();

  /**
   * Returns the number of EppResourceIndex buckets to be used.
   */
  public int getEppResourceIndexBucketCount();

  /**
   * Returns the base duration that gets doubled on each retry within {@code Ofy}.
   */
  public Duration getBaseOfyRetryDuration();

  /**
   * Returns the global automatic transfer length for contacts.  After this amount of time has
   * elapsed, the transfer is automatically improved.
   */
  public Duration getContactAutomaticTransferLength();

  /**
   * Returns the clientId of the registrar used by the {@code CheckApiServlet}.
   */
  public String getCheckApiServletRegistrarClientId();

  /**
   * Returns the delay before executing async delete flow mapreduces.
   *
   * <p>This delay should be sufficiently longer than a transaction, to solve the following problem:
   * <ul>
   *   <li>a domain mutation flow starts a transaction
   *   <li>the domain flow non-transactionally reads a resource and sees that it's not in
   *       PENDING_DELETE
   *   <li>the domain flow creates a new reference to this resource
   *   <li>a contact/host delete flow runs and marks the resource PENDING_DELETE and commits
   *   <li>the domain flow commits
   * </ul>
   *
   * <p>Although we try not to add references to a PENDING_DELETE resource, strictly speaking that
   * is ok as long as the mapreduce eventually sees the new reference (and therefore asynchronously
   * fails the delete). Without this delay, the mapreduce might have started before the domain flow
   * committed, and could potentially miss the reference.
   */
  public Duration getAsyncDeleteFlowMapreduceDelay();

  /**
   * Returns the amount of time to back off following an async flow task failure.
   *
   * This should be ~orders of magnitude larger than the rate on the queue, in order to prevent
   * the logs from filling up with unnecessarily failures.
   */
  public Duration getAsyncFlowFailureBackoff();
}
