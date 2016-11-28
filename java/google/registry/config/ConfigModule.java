// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dagger.Module;
import dagger.Provides;
import java.lang.annotation.Documented;
import java.net.URI;
import java.net.URL;
import javax.annotation.Nullable;
import javax.inject.Qualifier;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTimeConstants;
import org.joda.time.Duration;

/**
 * Configuration example for the Nomulus codebase.
 *
 * <p>The Nomulus codebase contains many classes that inject configurable settings. This is
 * the centralized class that is used by default to configure them all, in hard-coded type-safe
 * Java code.
 *
 * <p>This class does not represent the total configuration of the Nomulus service. It's
 * <b>only meant for settings that need to be configured <i>once</i></b>. Settings which may
 * be subject to change in the future, should instead be retrieved from Datastore. The
 * {@link google.registry.model.registry.Registry Registry} class is one such example of this.
 *
 * <h3>Customization</h3>
 *
 * <p>It is recommended that users do not modify this file within a forked repository. It is
 * preferable to modify these settings by swapping out this module with a separate copied version
 * in the user's repository. For this to work, other files need to be copied too, such as the
 * {@code @Component} instances under {@code google.registry.module}.  This allows modules to be
 * substituted at the {@code @Component} level.
 *
 * <p>There's also a deprecated configuration class that needs to be overridden and supplied via a
 * system property. See the instructions in {@link ProductionRegistryConfigExample} and
 * {@link RegistryConfigLoader}.
 */
@Module
public final class ConfigModule {

  /** Dagger qualifier for configuration settings. */
  @Qualifier
  @Documented
  public static @interface Config {
    String value() default "";
  }

  private static final RegistryEnvironment REGISTRY_ENVIRONMENT = RegistryEnvironment.get();

  @Provides
  public static RegistryEnvironment provideRegistryEnvironment() {
    return REGISTRY_ENVIRONMENT;
  }

  @Provides
  public static RegistryConfig provideConfig(RegistryEnvironment environment) {
    return environment.config();
  }

  /**
   * Returns the DPML TLD name
   *
   * @return String name of the DPML TLD name
   */
  @Provides
  @Config("dpmlTld")
  public static String provideDpmlTld() {
    return "dpml.zone";
  }

  @Provides
  @Config("projectId")
  public static String provideProjectId(RegistryConfig config) {
    return config.getProjectId();
  }

  /**
   * The filename of the logo to be displayed in the header of the registrar console.
   *
   * @see google.registry.ui.server.registrar.ConsoleUiAction
   */
  @Provides
  @Config("logoFilename")
  public static String provideLogoFilename(RegistryEnvironment environment) {
    switch (environment) {
      case UNITTEST:
      case LOCAL:
        return "logo.png";
      default:
        // Change this to the filename of your logo.
        return "google_registry.png";
    }
  }

  /**
   * The product name of this specific registry.  Used throughout the registrar console.
   *
   * @see google.registry.ui.server.registrar.ConsoleUiAction
   */
  @Provides
  @Config("productName")
  public static String provideProductName(RegistryEnvironment environment) {
    // Change this to the name of your product.
    return "Nomulus";
  }

  /**
   * The e-mail address for questions about integrating with the registry.  Used in the
   * "contact-us" section of the registrar console.
   *
   * @see google.registry.ui.server.registrar.ConsoleUiAction
   */
  @Provides
  @Config("integrationEmail")
  public static String provideIntegrationEmail(RegistryEnvironment environment) {
    // Change this to your integration email address.
    return "integration@example.com";
  }

  /**
   * The e-mail address for general support.  Used in the "contact-us" section of the registrar
   * console.
   *
   * @see google.registry.ui.server.registrar.ConsoleUiAction
   */
  @Provides
  @Config("supportEmail")
  public static String provideSupportEmail(RegistryEnvironment environment) {
    // Change this to your support email address.
    return "support@example.com";
  }

  /**
   * The "From" e-mail address for announcements.  Used in the "contact-us" section of the
   * registrar console.
   *
   * @see google.registry.ui.server.registrar.ConsoleUiAction
   */
  @Provides
  @Config("announcementsEmail")
  public static String provideAnnouncementsEmail(RegistryEnvironment environment) {
    // Change this to your announcements e-mail.
    return "announcements@example.com";
  }

  /**
   * The contact phone number.  Used in the "contact-us" section of the registrar console.
   *
   * @see google.registry.ui.server.registrar.ConsoleUiAction
   */
  @Provides
  @Config("supportPhoneNumber")
  public static String provideSupportPhoneNumber(RegistryEnvironment environment) {
    // Change this to your phone number.
    return "+1 (888) 555 0123";
  }

  /**
   * The URL for technical support docs.  Used in the "contact-us" section of the registrar console.
   *
   * @see google.registry.ui.server.registrar.ConsoleUiAction
   */
  @Provides
  @Config("technicalDocsUrl")
  public static String provideTechnicalDocsUrl(RegistryEnvironment environment) {
    // Change this to your support docs link.
    return "http://example.com/your_support_docs/";
  }

  /**
   * Returns the Google Cloud Storage bucket for storing zone files.
   *
   * @see google.registry.backup.ExportCommitLogDiffAction
   */
  @Provides
  @Config("zoneFilesBucket")
  public static String provideZoneFilesBucket(@Config("projectId") String projectId) {
    return projectId + "-zonefiles";
  }

  /**
   * Returns the Google Cloud Storage bucket for storing commit logs.
   *
   * @see google.registry.backup.ExportCommitLogDiffAction
   */
  @Provides
  @Config("commitLogGcsBucket")
  public static String provideCommitLogGcsBucket(@Config("projectId") String projectId) {
    return projectId + "-commits";
  }

  /** @see RegistryConfig#getCommitLogDatastoreRetention() */
  @Provides
  @Config("commitLogDatastoreRetention")
  public static Duration provideCommitLogDatastoreRetention(RegistryConfig config) {
    return config.getCommitLogDatastoreRetention();
  }

  /**
   * The GCS bucket for exporting domain lists.
   *
   * @see google.registry.export.ExportDomainListsAction
   */
  @Provides
  @Config("domainListsGcsBucket")
  public static String provideDomainListsGcsBucket(@Config("projectId") String projectId) {
    return projectId + "-domain-lists";
  }

  /**
   * Maximum number of commit logs to delete per transaction.
   *
   * <p>If we assume that the average key size is 256 bytes and that each manifest has six
   * mutations, we can do about 5,000 deletes in a single transaction before hitting the 10mB limit.
   * Therefore 500 should be a safe number, since it's an order of a magnitude less space than we
   * need.
   *
   * <p>Transactions also have a four minute time limit. Since we have to perform N subqueries to
   * fetch mutation keys, 500 would be a safe number if those queries were performed in serial,
   * since each query would have about 500ms to complete, which is an order a magnitude more time
   * than we need. However this does not apply, since the subqueries are performed asynchronously.
   *
   * @see google.registry.backup.DeleteOldCommitLogsAction
   */
  @Provides
  @Config("commitLogMaxDeletes")
  public static int provideCommitLogMaxDeletes() {
    return 500;
  }

  /**
   * Batch size for the number of transactions' worth of commit log data to process at once when
   * exporting a commit log diff.
   *
   * @see google.registry.backup.ExportCommitLogDiffAction
   */
  @Provides
  @Config("commitLogDiffExportBatchSize")
  public static int provideCommitLogDiffExportBatchSize() {
    return 100;
  }

  /**
   * Returns the Google Cloud Storage bucket for staging BRDA escrow deposits.
   *
   * @see google.registry.rde.PendingDepositChecker
   */
  @Provides
  @Config("brdaBucket")
  public static String provideBrdaBucket(@Config("projectId") String projectId) {
    return projectId + "-icann-brda";
  }

  /** @see google.registry.rde.BrdaCopyAction */
  @Provides
  @Config("brdaDayOfWeek")
  public static int provideBrdaDayOfWeek() {
    return DateTimeConstants.TUESDAY;
  }

  /**
   * Amount of time between BRDA deposits.
   *
   * @see google.registry.rde.PendingDepositChecker
   */
  @Provides
  @Config("brdaInterval")
  public static Duration provideBrdaInterval() {
    return Duration.standardDays(7);
  }

  /**
   * Returns {@code true} if the target zone should be created in DNS if it does not exist.
   */
  @Provides
  @Config("dnsCreateZone")
  public static boolean provideDnsCreateZone(RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
        return false;
      default:
        return true;
    }
  }

  /**
   * The maximum number of domain and host updates to batch together to send to
   * PublishDnsUpdatesAction, to avoid exceeding AppEngine's limits.
   *
   * @see google.registry.dns.ReadDnsQueueAction
   */
  @Provides
  @Config("dnsTldUpdateBatchSize")
  public static int provideDnsTldUpdateBatchSize() {
    return 100;
  }

  /**
   * The maximum interval (seconds) to lease tasks from the dns-pull queue.
   *
   * @see google.registry.dns.ReadDnsQueueAction
   * @see google.registry.dns.PublishDnsUpdatesAction
   */
  @Provides
  @Config("dnsWriteLockTimeout")
  public static Duration provideDnsWriteLockTimeout() {
    // Optimally, we would set this to a little less than the length of the DNS refresh cycle, since
    // otherwise, a new PublishDnsUpdatesAction could get kicked off before the current one has
    // finished, which will try and fail to acquire the lock. However, it is more important that it
    // be greater than the DNS write timeout, so that if that timeout occurs, it will be cleaned up
    // gracefully, rather than having the lock time out. So we have to live with the possible lock
    // failures.
    return Duration.standardSeconds(75);
  }

  /**
   * Returns the default time to live for DNS records.
   *
   * @see google.registry.dns.writer.clouddns.CloudDnsWriter
   */
  @Provides
  @Config("dnsDefaultTtl")
  public static Duration provideDnsDefaultTtl() {
    return Duration.standardSeconds(180);
  }

  /**
   * Number of sharded entity group roots used for performing strongly consistent scans.
   *
   * <p><b>Warning:</b> This number may increase but never decrease.
   *
   * @see google.registry.model.index.EppResourceIndex
   */
  @Provides
  @Config("eppResourceIndexBucketCount")
  public static int provideEppResourceIndexBucketCount(RegistryConfig config) {
    return config.getEppResourceIndexBucketCount();
  }

  /**
   * Returns size of Google Cloud Storage client connection buffer in bytes.
   *
   * @see google.registry.gcs.GcsUtils
   */
  @Provides
  @Config("gcsBufferSize")
  public static int provideGcsBufferSize() {
    return 1024 * 1024;
  }

  /**
   * Gets the email address of the admin account for the Google App.
   *
   * @see google.registry.groups.DirectoryGroupsConnection
   */
  @Provides
  @Config("googleAppsAdminEmailAddress")
  public static String provideGoogleAppsAdminEmailAddress(RegistryEnvironment environment) {
    // Change this to your admin account.
    return "admin@example.com";
  }

  /**
   * Returns the email address(es) that notifications of registrar and/or registrar contact updates
   * should be sent to, or the empty list if updates should not be sent.
   *
   * @see google.registry.ui.server.registrar.RegistrarSettingsAction
   */
  @Provides
  @Config("registrarChangesNotificationEmailAddresses")
  public static ImmutableList<String> provideRegistrarChangesNotificationEmailAddresses(
      RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
        // Change this to an appropriate notification e-mail address.
        return ImmutableList.of("notification@registry.example");
      case UNITTEST:
        return ImmutableList.of("notification@test.example", "notification2@test.example");
      default:
        return ImmutableList.<String>of();
    }
  }

  /**
   * Returns the publicly accessible domain name for the running Google Apps instance.
   *
   * @see google.registry.export.SyncGroupMembersAction
   * @see google.registry.tools.server.CreateGroupsAction
   */
  @Provides
  @Config("publicDomainName")
  public static String providePublicDomainName(RegistryEnvironment environment) {
    // Change this to your domain name.
    return "registry.example.com";
  }

  /**
   * Returns {@code true} if TMCH certificate authority should be in testing mode.
   *
   * @see RegistryConfig#getTmchCaTestingMode()
   */
  @Provides
  @Config("tmchCaTestingMode")
  public static boolean provideTmchCaTestingMode(RegistryConfig config) {
    return config.getTmchCaTestingMode();
  }

  /**
   * ICANN TMCH Certificate Revocation List URL.
   *
   * <p>This file needs to be downloaded at least once a day and verified to make sure it was
   * signed by {@code icann-tmch.crt}.
   *
   * @see google.registry.tmch.TmchCrlAction
   * @see "http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-5.2.3.2"
   */
  @Provides
  @Config("tmchCrlUrl")
  public static URL provideTmchCrlUrl(RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
        return makeUrl("http://crl.icann.org/tmch.crl");
      default:
        return makeUrl("http://crl.icann.org/tmch_pilot.crl");
    }
  }

  /**
   * URL prefix for communicating with MarksDB ry interface.
   *
   * <p>This URL is used for DNL, SMDRL, and LORDN.
   *
   * @see google.registry.tmch.Marksdb
   * @see google.registry.tmch.NordnUploadAction
   */
  @Provides
  @Config("tmchMarksdbUrl")
  public static String provideTmchMarksdbUrl(RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
      case UNITTEST:
        return "https://ry.marksdb.org";
      default:
        return "https://test.ry.marksdb.org";
    }
  }

  /**
   * Returns the Google Cloud Storage bucket for staging escrow deposits pending upload.
   *
   * @see google.registry.rde.RdeStagingAction
   */
  @Provides
  @Config("rdeBucket")
  public static String provideRdeBucket(@Config("projectId") String projectId) {
    return projectId + "-rde";
  }

  /**
   * Returns the Google Cloud Storage bucket for importing escrow files.
   *
   * @see google.registry.rde.RdeContactImportAction
   * @see google.registry.rde.RdeHostImportAction
   */
  @Provides
  @Config("rdeImportBucket")
  public static String provideRdeImportBucket(@Config("projectId") String projectId) {
    return projectId + "-rde-import";
  }

  /**
   * Size of Ghostryde buffer in bytes for each layer in the pipeline.
   *
   * @see google.registry.rde.Ghostryde
   */
  @Provides
  @Config("rdeGhostrydeBufferSize")
  public static Integer provideRdeGhostrydeBufferSize() {
    return 64 * 1024;
  }

  /**
   * Amount of time between RDE deposits.
   *
   * @see google.registry.rde.PendingDepositChecker
   * @see google.registry.rde.RdeReportAction
   * @see google.registry.rde.RdeUploadAction
   */
  @Provides
  @Config("rdeInterval")
  public static Duration provideRdeInterval() {
    return Duration.standardDays(1);
  }

  /**
   * Maximum amount of time for sending a small XML file to ICANN via HTTP, before killing.
   *
   * @see google.registry.rde.RdeReportAction
   */
  @Provides
  @Config("rdeReportLockTimeout")
  public static Duration provideRdeReportLockTimeout() {
    return Duration.standardSeconds(60);
  }

  /**
   * URL of ICANN's HTTPS server to which the RDE report should be {@code PUT}.
   *
   * <p>You must append {@code "/TLD/ID"} to this URL.
   *
   * @see google.registry.rde.RdeReportAction
   */
  @Provides
  @Config("rdeReportUrlPrefix")
  public static String provideRdeReportUrlPrefix(RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
        return "https://ry-api.icann.org/report/registry-escrow-report";
      default:
        return "https://test-ry-api.icann.org:8543/report/registry-escrow-report";
    }
  }

  /**
   * Size of RYDE generator buffer in bytes for each of the five layers.
   *
   * @see google.registry.rde.RydePgpCompressionOutputStream
   * @see google.registry.rde.RydePgpFileOutputStream
   * @see google.registry.rde.RydePgpSigningOutputStream
   * @see google.registry.rde.RydeTarOutputStream
   */
  @Provides
  @Config("rdeRydeBufferSize")
  public static Integer provideRdeRydeBufferSize() {
    return 64 * 1024;
  }

  /**
   * Maximum amount of time generating an escrow deposit for a TLD could take, before killing.
   *
   * @see google.registry.rde.RdeStagingReducer
   */
  @Provides
  @Config("rdeStagingLockTimeout")
  public static Duration provideRdeStagingLockTimeout() {
    return Duration.standardHours(5);
  }

  /**
   * Maximum amount of time it should ever take to upload an escrow deposit, before killing.
   *
   * @see google.registry.rde.RdeUploadAction
   */
  @Provides
  @Config("rdeUploadLockTimeout")
  public static Duration provideRdeUploadLockTimeout() {
    return Duration.standardMinutes(30);
  }

  /**
   * Minimum amount of time to wait between consecutive SFTP uploads on a single TLD.
   *
   * <p>This value was communicated to us by the escrow provider.
   *
   * @see google.registry.rde.RdeStagingReducer
   */
  @Provides
  @Config("rdeUploadSftpCooldown")
  public static Duration provideRdeUploadSftpCooldown() {
    return Duration.standardHours(2);
  }

  /**
   * Returns the identity (an email address) used for the SSH keys used in RDE SFTP uploads.
   *
   * @see google.registry.keyring.api.Keyring#getRdeSshClientPublicKey()
   * @see google.registry.keyring.api.Keyring#getRdeSshClientPrivateKey()
   */
  @Provides
  @Config("rdeSshIdentity")
  public static String provideSshIdentity() {
    return "mercury-donuts-test@example.test";
  }

  /**
   * Returns SFTP URL containing a username, hostname, port (optional), and directory (optional) to
   * which cloud storage files are uploaded. The password should not be included, as it's better to
   * use public key authentication.
   *
   * @see google.registry.rde.RdeUploadAction
   */
  @Provides
  @Config("rdeUploadUrl")
  public static URI provideRdeUploadUrl(RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
        return URI.create("sftp://GoogleTLD@sftpipm2.ironmountain.com/Outbox");
      default:
        return URI.create("sftp://google@ppftpipm.ironmountain.com/Outbox");
    }
  }

  /**
   * Whether or not the registrar console is enabled.
   *
   * @see google.registry.ui.server.registrar.ConsoleUiAction
   */
  @Provides
  @Config("registrarConsoleEnabled")
  public static boolean provideRegistrarConsoleEnabled() {
    return true;
  }

  /**
   * Maximum amount of time for syncing a spreadsheet, before killing.
   *
   * @see google.registry.export.sheet.SyncRegistrarsSheetAction
   */
  @Provides
  @Config("sheetLockTimeout")
  public static Duration provideSheetLockTimeout() {
    return Duration.standardHours(1);
  }

  /**
   * Returns ID of Google Spreadsheet to which Registrar entities should be synced.
   *
   * <p>This ID, as you'd expect, comes from the URL of the spreadsheet.
   *
   * @see google.registry.export.sheet.SyncRegistrarsSheetAction
   */
  @Provides
  @Config("sheetRegistrarId")
  public static Optional<String> provideSheetRegistrarId(RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
        return Optional.of("1n2Gflqsgo9iDXcdt9VEskOVySZ8qIhQHJgjqsleCKdE");
      case ALPHA:
      case CRASH:
        return Optional.of("16BwRt6v11Iw-HujCbAkmMxqw3sUG13B8lmXLo-uJTsE");
      case SANDBOX:
        return Optional.of("1TlR_UMCtfpkxT9oUEoF5JEbIvdWNkLRuURltFkJ_7_8");
      case QA:
        return Optional.of("1RoY1XZhLLwqBkrz0WbEtaT9CU6c8nUAXfId5BtM837o");
      default:
        return Optional.absent();
    }
  }

  /**
   * Returns SSH client connection and read timeout.
   *
   * @see google.registry.rde.RdeUploadAction
   */
  @Provides
  @Config("sshTimeout")
  public static Duration provideSshTimeout() {
    return Duration.standardSeconds(30);
  }

  /**
   * Duration after watermark where we shouldn't deposit, because transactions might be pending.
   *
   * @see google.registry.rde.RdeStagingAction
   */
  @Provides
  @Config("transactionCooldown")
  public static Duration provideTransactionCooldown() {
    return Duration.standardMinutes(5);
  }

  /**
   * Number of times to retry a GAE operation when {@code TransientFailureException} is thrown.
   *
   * <p>The number of milliseconds it'll sleep before giving up is {@code 2^n - 2}.
   *
   * @see google.registry.util.TaskEnqueuer
   */
  @Provides
  @Config("transientFailureRetries")
  public static int provideTransientFailureRetries() {
    return 12;  // Four seconds.
  }

  /**
   * Amount of time public HTTP proxies are permitted to cache our WHOIS responses.
   *
   * @see google.registry.whois.WhoisHttpServer
   */
  @Provides
  @Config("whoisHttpExpires")
  public static Duration provideWhoisHttpExpires() {
    return Duration.standardDays(1);
  }

  /**
   * Maximum number of results to return for an RDAP search query
   *
   * @see google.registry.rdap.RdapActionBase
   */
  @Provides
  @Config("rdapResultSetMaxSize")
  public static int provideRdapResultSetMaxSize() {
    return 100;
  }

  /**
   * Base for RDAP link paths.
   *
   * @see google.registry.rdap.RdapActionBase
   */
  @Provides
  @Config("rdapLinkBase")
  public static String provideRdapLinkBase() {
    return "https://nic.google/rdap/";
  }

  /**
   * WHOIS server displayed in RDAP query responses. As per Gustavo Lozano of ICANN, this should be
   * omitted, but the ICANN operational profile doesn't actually say that, so it's good to have the
   * ability to reinstate this field if necessary.
   *
   * @see google.registry.rdap.RdapActionBase
   */
  @Nullable
  @Provides
  @Config("rdapWhoisServer")
  public static String provideRdapWhoisServer() {
    return null;
  }

  /**
   * Returns Braintree Merchant Account IDs for each supported currency.
   *
   * @see google.registry.ui.server.registrar.RegistrarPaymentAction
   * @see google.registry.ui.server.registrar.RegistrarPaymentSetupAction
   */
  @Provides
  @Config("braintreeMerchantAccountIds")
  public static ImmutableMap<CurrencyUnit, String> provideBraintreeMerchantAccountId(
      RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
        return ImmutableMap.of(
            CurrencyUnit.USD, "charlestonregistryUSD",
            CurrencyUnit.JPY, "charlestonregistryJPY");
      default:
        return ImmutableMap.of(
            CurrencyUnit.USD, "google",
            CurrencyUnit.JPY, "google-jpy");
    }
  }

  /**
   * Returns Braintree Merchant ID of Registry, used for accessing Braintree API.
   *
   * <p>This is a base32 value copied from the Braintree website.
   *
   * @see google.registry.braintree.BraintreeModule
   */
  @Provides
  @Config("braintreeMerchantId")
  public static String provideBraintreeMerchantId(RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
        return "6gm2mm48k9ty4zmx";
      default:
        // Valentine: Nomulus Braintree Sandbox
        return "vqgn8khkq2cs6y9s";
    }
  }

  /**
   * Returns Braintree Public Key of Registry, used for accessing Braintree API.
   *
   * <p>This is a base32 value copied from the Braintree website.
   *
   * @see google.registry.braintree.BraintreeModule
   * @see google.registry.keyring.api.Keyring#getBraintreePrivateKey()
   */
  @Provides
  @Config("braintreePublicKey")
  public static String provideBraintreePublicKey(RegistryEnvironment environment) {
    switch (environment) {
      case PRODUCTION:
        return "tzcfxggzgbh2jg5x";
      default:
        // Valentine: Nomulus Braintree Sandbox
        return "tzcyzvm3mn7zkdnx";
    }
  }

  /**
   * Disclaimer displayed at the end of WHOIS query results.
   *
   * @see google.registry.whois.WhoisResponse
   */
  @Provides
  @Config("whoisDisclaimer")
  public static String provideWhoisDisclaimer() {
    return "Terms of Use: Users accessing the Donuts WHOIS service must agree to use the data "
        + "only for lawful purposes, and under under no circumstances use the data to: Allow, "
        + "enable, or otherwise support the transmission by e-mail, telephone, or facsimile "
        + "of mass unsolicited, commercial advertising or solicitations to entities other "
        + "than the registrar's own existing customers. Enable high volume, automated, "
        + "electronic processes that send queries or data to the systems of Donuts or "
        + "any ICANN-accredited registrar, except as reasonably necessary to register "
        + "domain names or modify existing registrations. When using the Donuts Whois "
        + "service, please consider the following: The Whois service is not a replacement "
        + "for standard EPP commands to the SRS service. Whois is not considered authoritative "
        + "for registered domain objects. The Whois service may be scheduled for downtime "
        + "during production or OT&E maintenance periods. Queries to the Whois services are "
        + "throttled. If too many queries are received from a single IP address within a "
        + "specified time, the service will begin to reject further queries for a period of "
        + "time to prevent disruption of Whois service access.";
  }

  /**
   * Maximum QPS for the Google Cloud Monitoring V3 (aka Stackdriver) API. The QPS limit can be
   * adjusted by contacting Cloud Support.
   *
   * @see google.registry.monitoring.metrics.StackdriverWriter
   */
  @Provides
  @Config("stackdriverMaxQps")
  public static int provideStackdriverMaxQps() {
    return 30;
  }

  /**
   * Maximum number of points that can be sent to Stackdriver in a single TimeSeries.Create API
   * call.
   *
   * @see google.registry.monitoring.metrics.StackdriverWriter
   */
  @Provides
  @Config("stackdriverMaxPointsPerRequest")
  public static int provideStackdriverMaxPointsPerRequest() {
    return 200;
  }

  /**
   * The reporting interval, for BigQueryMetricsEnqueuer to be sent to a {@link
   * google.registry.monitoring.metrics.MetricWriter}.
   *
   * @see google.registry.monitoring.metrics.MetricReporter
   */
  @Provides
  @Config("metricsWriteInterval")
  public static Duration provideMetricsWriteInterval() {
    return Duration.standardSeconds(60);
  }

  /**
   * The time between a contact transfer request and its expiration date.
   *
   * @see google.registry.flows.contact.ContactTransferRequestFlow
   */
  @Provides
  @Config("contactAutomaticTransferLength")
  public static Duration provideContactAutomaticTransferLength(RegistryConfig config) {
    return config.getContactAutomaticTransferLength();
  }

  /**
   * Returns the maximum number of entities that can be checked at one time in an EPP check flow.
   */
  @Provides
  @Config("maxChecks")
  public static int provideMaxChecks() {
    return 50;
  }

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
   *
   * @see google.registry.flows.async.AsyncFlowEnqueuer
   */
  @Provides
  @Config("asyncDeleteFlowMapreduceDelay")
  public static Duration provideAsyncDeleteFlowMapreduceDelay() {
    return Duration.standardSeconds(90);
  }

  /**
   * The server ID used in the 'svID' element of an EPP 'greeting'.
   *
   * @see <a href="https://tools.ietf.org/html/rfc5730">RFC 7530</a>
   */
  @Provides
  @Config("greetingServerId")
  public static String provideGreetingServerId() {
    return "Donuts, Inc";
  }

  @Provides
  @Config("customLogicFactoryClass")
  public static String provideCustomLogicFactoryClass() {
    // TODO(b/32875427): This will be moved into configuration in a text file in a future refactor.
    return "google.registry.flows.custom.CustomLogicFactory";
  }
}
