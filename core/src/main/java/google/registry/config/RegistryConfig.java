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

package google.registry.config;

import static com.google.common.base.Suppliers.memoize;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static google.registry.config.ConfigUtils.makeUrl;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Comparator.naturalOrder;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import dagger.Module;
import dagger.Provides;
import google.registry.bsa.UploadBsaUnavailableDomainsAction;
import google.registry.dns.ReadDnsRefreshRequestsAction;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.request.Action.GkeService;
import google.registry.util.RegistryEnvironment;
import google.registry.util.YamlUtils;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.Duration;

/**
 * Central clearing-house for all configuration.
 *
 * <p>This class does not represent the total configuration of the Nomulus service. It's <b>only
 * meant for settings that need to be configured <i>once</i></b>. Settings which may be subject to
 * change in the future, should instead be retrieved from the database. The {@link
 * google.registry.model.tld.Tld Tld} class is one such example of this.
 *
 * <p>Note: Only settings that are actually configurable belong in this file. It's not a catch-all
 * for anything widely used throughout the code base.
 */
public final class RegistryConfig {

  private static final String ENVIRONMENT_CONFIG_FORMAT = "files/nomulus-config-%s.yaml";
  private static final String YAML_CONFIG_PROD =
      readResourceUtf8(RegistryConfig.class, "files/default-config.yaml");

  /** Dagger qualifier for configuration settings. */
  @Qualifier
  @Retention(RUNTIME)
  @Documented
  public @interface Config {
    String value() default "";
  }

  /**
   * Loads the {@link RegistryConfigSettings} POJO from the YAML configuration files.
   *
   * <p>The {@code default-config.yaml} file in this directory is loaded first, and a fatal error is
   * thrown if it cannot be found or if there is an error parsing it. Separately, the
   * environment-specific config file named {@code nomulus-config-ENVIRONMENT.yaml} is also loaded
   * and those values merged into the POJO.
   */
  static RegistryConfigSettings getConfigSettings() {
    String configFilePath =
        String.format(
            ENVIRONMENT_CONFIG_FORMAT, Ascii.toLowerCase(RegistryEnvironment.get().name()));
    String customYaml = readResourceUtf8(RegistryConfig.class, configFilePath);
    return YamlUtils.getConfigSettings(YAML_CONFIG_PROD, customYaml, RegistryConfigSettings.class);
  }

  /** Dagger module for providing configuration settings. */
  @Module
  public static final class ConfigModule {

    private ConfigModule() {}

    @Provides
    @Config("projectId")
    public static String provideProjectId(RegistryConfigSettings config) {
      return config.gcpProject.projectId;
    }

    @Provides
    @Config("projectIdNumber")
    public static long provideProjectIdNumber(RegistryConfigSettings config) {
      return config.gcpProject.projectIdNumber;
    }

    @Provides
    @Config("backendServiceIds")
    public static Map<String, Long> provideBackendServiceIds(RegistryConfigSettings config) {
      return config.gcpProject.backendServiceIds;
    }

    @Provides
    @Config("baseDomain")
    public static String provideBaseDomain(RegistryConfigSettings config) {
      return config.gcpProject.baseDomain;
    }

    @Provides
    @Config("locationId")
    public static String provideLocationId(RegistryConfigSettings config) {
      return config.gcpProject.locationId;
    }

    /**
     * The product name of this specific registry. Used throughout the registrar console.
     *
     * @see google.registry.ui.server.console.ConsoleUserDataAction
     */
    @Provides
    @Config("productName")
    public static String provideProductName(RegistryConfigSettings config) {
      return config.registryPolicy.productName;
    }

    /**
     * Returns the roid suffix to be used for the roids of all contacts and hosts. E.g. a value of
     * "ROID" would end up creating roids that look like "ABC123-ROID".
     *
     * @see <a href="http://www.iana.org/assignments/epp-repository-ids/epp-repository-ids.xhtml">
     *     Extensible Provisioning Protocol (EPP) Repository Identifiers</a>
     */
    @Provides
    @Config("contactAndHostRoidSuffix")
    public static String provideContactAndHostRoidSuffix(RegistryConfigSettings config) {
      return config.registryPolicy.contactAndHostRoidSuffix;
    }

    @Provides
    @Config("isEmailSendingEnabled")
    public static boolean provideIsEmailSendingEnabled(RegistryConfigSettings config) {
      return config.misc.isEmailSendingEnabled;
    }

    /**
     * The e-mail address for general support. Used in the "contact-us" section of the registrar
     * console.
     *
     * @see google.registry.ui.server.console.ConsoleUserDataAction
     */
    @Provides
    @Config("supportEmail")
    public static String provideSupportEmail(RegistryConfigSettings config) {
      return config.registrarConsole.supportEmailAddress;
    }

    /**
     * The DUM file name, used as a file name base for DUM csv file
     *
     * @see google.registry.ui.server.console.ConsoleDumDownloadAction
     */
    @Provides
    @Config("dumFileName")
    public static String provideDumFileName(RegistryConfigSettings config) {
      return config.registrarConsole.dumFileName;
    }

    /**
     * The contact phone number. Used in the "contact-us" section of the registrar console.
     *
     * @see google.registry.ui.server.console.ConsoleUserDataAction
     */
    @Provides
    @Config("supportPhoneNumber")
    public static String provideSupportPhoneNumber(RegistryConfigSettings config) {
      return config.registrarConsole.supportPhoneNumber;
    }

    /**
     * The URL for technical support docs. Used in the "contact-us" section of the registrar
     * console.
     *
     * @see google.registry.ui.server.console.ConsoleUserDataAction
     */
    @Provides
    @Config("technicalDocsUrl")
    public static String provideTechnicalDocsUrl(RegistryConfigSettings config) {
      return config.registrarConsole.technicalDocsUrl;
    }

    /**
     * Returns the Google Cloud Storage bucket for storing zone files.
     *
     * @see google.registry.tools.server.GenerateZoneFilesAction
     */
    @Provides
    @Config("zoneFilesBucket")
    public static String provideZoneFilesBucket(@Config("projectId") String projectId) {
      return projectId + "-zonefiles";
    }

    /**
     * Returns the length of time before commit logs should be deleted from the database.
     *
     * @see RegistryConfig#getDatabaseRetention()
     */
    @Provides
    @Config("databaseRetention")
    public static Duration provideDatabaseRetention() {
      return getDatabaseRetention();
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
     * The GCS bucket for exporting lists of unavailable names for the BSA.
     *
     * @see UploadBsaUnavailableDomainsAction
     */
    @Provides
    @Config("bsaUnavailableDomainsGcsBucket")
    public static String provideBsaUnavailableNamesGcsBucket(
        @Config("projectId") String projectId) {
      return projectId + "-bsa-unavailable-domains";
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

    /**
     * Returns the day of the week on which BRDA deposits should be made.
     *
     * @see google.registry.rde.BrdaCopyAction
     */
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
     * The maximum number of domain and host updates to batch together to send to
     * PublishDnsUpdatesAction, to avoid exceeding HTTP request timeout limits.
     *
     * @see ReadDnsRefreshRequestsAction
     */
    @Provides
    @Config("dnsTldUpdateBatchSize")
    public static int provideDnsTldUpdateBatchSize() {
      return 100;
    }

    /**
     * The maximum time we allow publishDnsUpdates to run.
     *
     * <p>This is the maximum lock duration for publishing the DNS updates, meaning it should allow
     * the various DnsWriters to publish and commit an entire batch (with a maximum number of items
     * set by provideDnsTldUpdateBatchSize).
     *
     * <p>Any update that takes longer than this timeout will be killed and retried from scratch.
     * Hence, a timeout that's too short can result in batches that retry over and over again,
     * failing forever.
     *
     * <p>If there are lock contention issues, they should be solved by changing the batch sizes or
     * the cron job rate, NOT by making this value smaller.
     *
     * @see google.registry.dns.PublishDnsUpdatesAction
     */
    @Provides
    @Config("publishDnsUpdatesLockDuration")
    public static Duration providePublishDnsUpdatesLockDuration() {
      return Duration.standardMinutes(3);
    }

    /**
     * The requested maximum duration for {@link ReadDnsRefreshRequestsAction}.
     *
     * <p>{@link ReadDnsRefreshRequestsAction} reads refresh requests from {@link DnsRefreshRequest}
     * It will continue reading requests until either no requests exist that matche the condition,
     * or this duration has passed.
     *
     * <p>This time is the maximum duration between the first and last attempt to read requests from
     * {@link DnsRefreshRequest}. The actual running time might be slightly longer, as we process
     * the requests.
     *
     * <p>The requests that are read will not be read again by any action until after this period
     * has passed, so concurrent runs (or runs that are very close to each other) of {@link
     * ReadDnsRefreshRequestsAction} will not keep reading the same requests with the earliest
     * request time.
     *
     * <p>Still, this value should ideally be less than the cloud scheduler job repeat rate for
     * {@link ReadDnsRefreshRequestsAction}, to not waste resources on multiple actions running at
     * the same time.
     *
     * <p>see google.registry.dns.ReadDnsRefreshRequestsAction
     */
    @Provides
    @Config("readDnsRefreshRequestsActionRuntime")
    public static Duration provideReadDnsRefreshRequestsRuntime() {
      return Duration.standardSeconds(45);
    }

    /**
     * Returns the default time to live for DNS A and AAAA records.
     *
     * @see google.registry.dns.writer.clouddns.CloudDnsWriter
     */
    @Provides
    @Config("dnsDefaultATtl")
    public static Duration provideDnsDefaultATtl() {
      return Duration.standardHours(1);
    }

    /**
     * Returns the default time to live for DNS NS records.
     *
     * @see google.registry.dns.writer.clouddns.CloudDnsWriter
     */
    @Provides
    @Config("dnsDefaultNsTtl")
    public static Duration provideDnsDefaultNsTtl() {
      return Duration.standardHours(3);
    }

    /**
     * Returns the default time to live for DNS DS records.
     *
     * @see google.registry.dns.writer.clouddns.CloudDnsWriter
     */
    @Provides
    @Config("dnsDefaultDsTtl")
    public static Duration provideDnsDefaultDsTtl() {
      return Duration.standardHours(1);
    }

    @Provides
    @Config("cloudSqlJdbcUrl")
    public static String provideCloudSqlJdbcUrl(RegistryConfigSettings config) {
      return config.cloudSql.jdbcUrl;
    }

    @Provides
    @Config("cloudSqlInstanceConnectionName")
    public static String provideCloudSqlInstanceConnectionName(RegistryConfigSettings config) {
      return config.cloudSql.instanceConnectionName;
    }

    @Provides
    @Config("cloudSqlReplicaInstanceConnectionName")
    public static Optional<String> provideCloudSqlReplicaInstanceConnectionName(
        RegistryConfigSettings config) {
      return Optional.ofNullable(config.cloudSql.replicaInstanceConnectionName);
    }

    @Provides
    @Config("cloudSqlDbInstanceName")
    public static String provideCloudSqlDbInstance(RegistryConfigSettings config) {
      // Format of instanceConnectionName: project-id:region:instance-name
      int lastColonIndex = config.cloudSql.instanceConnectionName.lastIndexOf(':');
      return config.cloudSql.instanceConnectionName.substring(lastColonIndex + 1);
    }

    @Provides
    @Config("cloudDnsRootUrl")
    public static Optional<String> getCloudDnsRootUrl(RegistryConfigSettings config) {
      return Optional.ofNullable(config.cloudDns.rootUrl);
    }

    @Provides
    @Config("cloudDnsServicePath")
    public static Optional<String> getCloudDnsServicePath(RegistryConfigSettings config) {
      return Optional.ofNullable(config.cloudDns.servicePath);
    }

    /**
     * Returns the email address of the admin account on the G Suite app used to perform
     * administrative actions.
     *
     * @see google.registry.groups.DirectoryGroupsConnection
     */
    @Provides
    @Config("gSuiteAdminAccountEmailAddress")
    public static String provideGSuiteAdminAccountEmailAddress(RegistryConfigSettings config) {
      return config.gSuite.adminAccountEmailAddress;
    }

    /**
     * Returns the email address of the group containing emails of support accounts.
     *
     * <p>These accounts will have "ADMIN" access to the registrar console.
     *
     * @see google.registry.groups.DirectoryGroupsConnection
     */
    @Provides
    @Config("gSuiteSupportGroupEmailAddress")
    public static Optional<String> provideGSuiteSupportGroupEmailAddress(
        RegistryConfigSettings config) {
      return Optional.ofNullable(Strings.emptyToNull(config.gSuite.supportGroupEmailAddress));
    }

    /**
     * Returns the email address of the group containing emails of console users.
     *
     * <p>This group should be granted the {@code roles/iap.httpsResourceAccessor} role.
     */
    @Provides
    @Config("gSuiteConsoleUserGroupEmailAddress")
    public static Optional<String> provideGSuiteConsoleUserGroupEmailAddress(
        RegistryConfigSettings config) {
      return Optional.ofNullable(Strings.emptyToNull(config.gSuite.consoleUserGroupEmailAddress));
    }

    /**
     * Returns the email address(es) that notifications of registrar and/or registrar contact
     * updates should be sent to, or the empty list if updates should not be sent.
     *
     * @see google.registry.ui.server.SendEmailUtils
     */
    @Provides
    @Config("registrarChangesNotificationEmailAddresses")
    public static ImmutableList<String> provideRegistrarChangesNotificationEmailAddresses(
        RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.registryPolicy.registrarChangesNotificationEmailAddresses);
    }

    /**
     * Returns the publicly accessible domain name for the running G Suite instance.
     *
     * @see google.registry.export.SyncGroupMembersAction
     * @see google.registry.tools.server.CreateGroupsAction
     */
    @Provides
    @Config("gSuiteDomainName")
    public static String provideGSuiteDomainName(RegistryConfigSettings config) {
      return config.gSuite.domainName;
    }

    /**
     * Returns the mode that TMCH certificate authority should run in.
     *
     * @see google.registry.tmch.TmchCertificateAuthority
     */
    @Provides
    @Config("tmchCaMode")
    public static TmchCaMode provideTmchCaMode(RegistryConfigSettings config) {
      return TmchCaMode.valueOf(config.registryPolicy.tmchCaMode);
    }

    /** The mode that the {@code TmchCertificateAuthority} operates in. */
    public enum TmchCaMode {

      /** Production mode, suitable for live environments hosting TLDs. */
      PRODUCTION,

      /** Pilot mode, for everything else (e.g. sandbox). */
      PILOT
    }

    /**
     * ICANN TMCH Certificate Revocation List URL.
     *
     * <p>This file needs to be downloaded at least once a day and verified to make sure it was
     * signed by {@code icann-tmch.crt} or {@code icann-tmch-pilot.crt} depending on TMCH CA mode.
     *
     * @see google.registry.tmch.TmchCrlAction
     * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-5.2.3.2">TMCH
     *     RFC</a>
     */
    @Provides
    @Config("tmchCrlUrl")
    public static URL provideTmchCrlUrl(RegistryConfigSettings config) {
      return makeUrl(config.registryPolicy.tmchCrlUrl);
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
    public static String provideTmchMarksdbUrl(RegistryConfigSettings config) {
      return config.registryPolicy.tmchMarksDbUrl;
    }

    /**
     * The email address that outgoing emails from the app are sent from.
     *
     * @see google.registry.ui.server.SendEmailUtils
     */
    @Provides
    @Config("gSuiteOutgoingEmailAddress")
    public static InternetAddress provideGSuiteOutgoingEmailAddress(RegistryConfigSettings config) {
      return parseEmailAddress(config.gSuite.outgoingEmailAddress);
    }

    // TODO(b/279671974): reuse the 'gSuiteOutgoingEmailAddress' annotation after migration
    @Provides
    @Config("gSuiteNewOutgoingEmailAddress")
    public static String provideGSuiteNewOutgoingEmailAddress(RegistryConfigSettings config) {
      return config.gSuite.newOutgoingEmailAddress;
    }

    /**
     * The display name that is used on outgoing emails sent by Nomulus.
     *
     * @see google.registry.ui.server.SendEmailUtils
     */
    @Provides
    @Config("gSuiteOutgoingEmailDisplayName")
    public static String provideGSuiteOutgoingEmailDisplayName(RegistryConfigSettings config) {
      return config.gSuite.outgoingEmailDisplayName;
    }

    /**
     * Provides the `reply-to` address for outgoing email messages. This address may be outside the
     * GSuite domain.
     */
    @Provides
    @Config("replyToEmailAddress")
    public static InternetAddress provideReplyToEmailAddress(RegistryConfigSettings config) {
      return parseEmailAddress(config.gSuite.replyToEmailAddress);
    }

    /**
     * Returns whether an SSL certificate hash is required to log in via EPP and run flows.
     *
     * @see google.registry.flows.TlsCredentials
     */
    @Provides
    @Config("requireSslCertificates")
    public static boolean provideRequireSslCertificates(RegistryConfigSettings config) {
      return config.registryPolicy.requireSslCertificates;
    }

    /**
     * Returns the GCE machine type that a CPU-demanding pipeline should use.
     *
     * @see google.registry.beam.rde.RdePipeline
     */
    @Provides
    @Config("highPerformanceMachineType")
    public static String provideHighPerformanceMachineType(RegistryConfigSettings config) {
      return config.beam.highPerformanceMachineType;
    }

    /**
     * Returns initial number of workers used for a Beam pipeline. Autoscaling can still in effect.
     *
     * @see <a
     *     href=https://cloud.google.com/dataflow/docs/guides/deploying-a-pipeline#horizontal-autoscaling>
     *     Horizontal Autoscaling </a>
     */
    @Provides
    @Config("initialWorkerCount")
    public static int provideInitialWorkerCount(RegistryConfigSettings config) {
      return config.beam.initialWorkerCount;
    }

    /**
     * Returns the default job region to run Apache Beam (Cloud Dataflow) jobs in.
     *
     * @see google.registry.beam.billing.InvoicingPipeline
     * @see google.registry.beam.spec11.Spec11Pipeline
     * @see google.registry.beam.billing.InvoicingPipeline
     */
    @Provides
    @Config("defaultJobRegion")
    public static String provideDefaultJobRegion(RegistryConfigSettings config) {
      return config.beam.defaultJobRegion;
    }

    /** Returns the GCS bucket URL with all staged BEAM flex templates. */
    @Provides
    @Config("beamStagingBucketUrl")
    public static String provideBeamStagingBucketUrl(RegistryConfigSettings config) {
      return config.beam.stagingBucketUrl;
    }

    /**
     * Returns the Google Cloud Storage bucket for Spec11 and ICANN transaction and activity reports
     * to be uploaded.
     *
     * @see google.registry.reporting.icann.IcannReportingUploadAction
     */
    @Provides
    @Config("reportingBucket")
    public static String provideReportingBucket(@Config("projectId") String projectId) {
      return projectId + "-reporting";
    }

    /**
     * Returns the Google Cloud Storage bucket URL for Spec11 and ICANN transaction and activity
     * reports to be uploaded.
     *
     * @see google.registry.reporting.icann.IcannReportingUploadAction
     * @see google.registry.reporting.spec11.PublishSpec11ReportAction
     */
    @Provides
    @Config("reportingBucketUrl")
    public static String provideReportingBucketUrl(
        @Config("reportingBucket") String reportingBucket) {
      return "gs://" + reportingBucket;
    }

    /**
     * Returns the URL we send HTTP PUT requests for ICANN monthly transactions reports.
     *
     * @see google.registry.reporting.icann.IcannHttpReporter
     */
    @Provides
    @Config("icannTransactionsReportingUploadUrl")
    public static String provideIcannTransactionsReportingUploadUrl(RegistryConfigSettings config) {
      return config.icannReporting.icannTransactionsReportingUploadUrl;
    }

    /**
     * Returns the URL we send HTTP PUT requests for ICANN monthly activity reports.
     *
     * @see google.registry.reporting.icann.IcannHttpReporter
     */
    @Provides
    @Config("icannActivityReportingUploadUrl")
    public static String provideIcannActivityReportingUploadUrl(RegistryConfigSettings config) {
      return config.icannReporting.icannActivityReportingUploadUrl;
    }

    /**
     * Returns name of the GCS bucket we store invoices and detail reports in.
     *
     * @see google.registry.reporting.billing.CopyDetailReportsAction
     * @see google.registry.reporting.billing.BillingEmailUtils
     */
    @Provides
    @Config("billingBucket")
    public static String provideBillingBucket(@Config("projectId") String projectId) {
      return projectId + "-billing";
    }

    /**
     * Returns the URL of the GCS bucket we store invoices and detail reports in.
     *
     * @see google.registry.beam.billing.InvoicingPipeline
     */
    @Provides
    @Config("billingBucketUrl")
    public static String provideBillingBucketUrl(@Config("billingBucket") String billingBucket) {
      return "gs://" + billingBucket;
    }

    /**
     * Returns origin part of the URL of the billing invoice file
     *
     * @see google.registry.beam.billing.InvoicingPipeline
     */
    @Provides
    @Config("billingInvoiceOriginUrl")
    public static String provideBillingInvoiceOriginUrl(RegistryConfigSettings config) {
      return config.billing.billingInvoiceOriginUrl;
    }

    /**
     * Returns whether or not we should publish invoices to partners automatically by default.
     *
     * @see google.registry.reporting.billing.BillingModule
     */
    @Provides
    @Config("defaultShouldPublishInvoices")
    public static boolean provideDefaultShouldPublishInvoices() {
      return false;
    }

    /**
     * Returns the list of addresses that receive monthly invoicing emails.
     *
     * @see google.registry.reporting.billing.BillingEmailUtils
     */
    @Provides
    @Config("invoiceEmailRecipients")
    public static ImmutableList<InternetAddress> provideInvoiceEmailRecipients(
        RegistryConfigSettings config) {
      return config.billing.invoiceEmailRecipients.stream()
          .map(RegistryConfig::parseEmailAddress)
          .collect(toImmutableList());
    }

    /**
     * Returns an optional return email address that overrides the default {@code reply-to} address
     * in outgoing invoicing email messages.
     */
    @Provides
    @Config("invoiceReplyToEmailAddress")
    public static Optional<InternetAddress> provideInvoiceReplyToEmailAddress(
        RegistryConfigSettings config) {
      return Optional.ofNullable(config.billing.invoiceReplyToEmailAddress)
          .map(RegistryConfig::parseEmailAddress);
    }

    /**
     * Returns the file prefix for the invoice CSV file.
     *
     * @see google.registry.beam.billing.InvoicingPipeline
     * @see google.registry.reporting.billing.BillingEmailUtils
     */
    @Provides
    @Config("invoiceFilePrefix")
    public static String provideInvoiceFilePrefix(RegistryConfigSettings config) {
      return config.billing.invoiceFilePrefix;
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
      return Duration.standardMinutes(1);
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
    public static String provideRdeReportUrlPrefix(RegistryConfigSettings config) {
      return config.rde.reportUrlPrefix;
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
     * @see google.registry.rde.RdeUploadAction
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
    public static String provideSshIdentity(RegistryConfigSettings config) {
      return config.rde.sshIdentityEmailAddress;
    }

    /**
     * Returns SFTP URL containing a username, hostname, port (optional), and directory (optional)
     * to which cloud storage files are uploaded. The password should not be included, as it's
     * better to use public key authentication.
     *
     * @see google.registry.rde.RdeUploadAction
     */
    @Provides
    @Config("rdeUploadUrl")
    public static URI provideRdeUploadUrl(RegistryConfigSettings config) {
      return URI.create(config.rde.uploadUrl);
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
    public static Optional<String> provideSheetRegistrarId(RegistryConfigSettings config) {
      return Optional.ofNullable(config.misc.sheetExportId);
    }

    /**
     * Returns the desired delay between outgoing emails when sending in bulk.
     *
     * <p>Gmail apparently has unpublished limits on peak throughput over short period.
     */
    @Provides
    @Config("emailThrottleDuration")
    public static Duration provideEmailThrottleSeconds(RegistryConfigSettings config) {
      return Duration.standardSeconds(config.misc.emailThrottleSeconds);
    }

    /**
     * Returns the email address we send various alert e-mails to.
     *
     * <p>This allows us to easily verify the success or failure of periodic tasks by passively
     * checking e-mail.
     *
     * @see google.registry.reporting.billing.BillingEmailUtils
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("alertRecipientEmailAddress")
    public static InternetAddress provideAlertRecipientEmailAddress(RegistryConfigSettings config) {
      return parseEmailAddress(config.misc.alertRecipientEmailAddress);
    }

    // TODO(b/279671974): remove below method after migration
    @Provides
    @Config("newAlertRecipientEmailAddress")
    public static InternetAddress provideNewAlertRecipientEmailAddress(
        RegistryConfigSettings config) {
      return parseEmailAddress(config.misc.newAlertRecipientEmailAddress);
    }

    /**
     * Returns the email address to which spec 11 email should be replied.
     *
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("spec11OutgoingEmailAddress")
    public static InternetAddress provideSpec11OutgoingEmailAddress(RegistryConfigSettings config) {
      return parseEmailAddress(config.misc.spec11OutgoingEmailAddress);
    }

    /**
     * Returns the email addresses to which we will BCC Spec11 emails.
     *
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("spec11BccEmailAddresses")
    public static ImmutableList<InternetAddress> provideSpec11BccEmailAddresses(
        RegistryConfigSettings config) {
      return config.misc.spec11BccEmailAddresses.stream()
          .map(RegistryConfig::parseEmailAddress)
          .collect(toImmutableList());
    }

    /**
     * Returns the name of the registry, for use in spec 11 emails.
     *
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("registryName")
    public static String provideRegistryName(RegistryConfigSettings config) {
      return config.registryPolicy.registryName;
    }

    /**
     * Returns a list of resources we send to registrars when informing them of spec 11 threats.
     *
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("spec11WebResources")
    public static ImmutableList<String> provideSpec11WebResources(RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.registryPolicy.spec11WebResources);
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
     * <p>The number of milliseconds it'll sleep before giving up is {@code (2^n - 2) * 100}.
     *
     * <p>Note that this uses {@code @Named} instead of {@code @Config} so that it can be used from
     * the low-level util package, which cannot have a dependency on the config package.
     *
     * @see google.registry.batch.CloudTasksUtils
     */
    @Provides
    @Named("transientFailureRetries")
    public static int provideTransientFailureRetries(RegistryConfigSettings config) {
      return config.misc.transientFailureRetries;
    }

    /**
     * Amount of time public HTTP proxies are permitted to cache our WHOIS responses.
     *
     * @see google.registry.whois.WhoisHttpAction
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
     * Redaction text for email address in WHOIS
     *
     * @see google.registry.whois.WhoisResponse
     */
    @Provides
    @Config("whoisRedactedEmailText")
    public static String provideWhoisRedactedEmailText(RegistryConfigSettings config) {
      return config.registryPolicy.whoisRedactedEmailText;
    }

    /**
     * Disclaimer displayed at the end of WHOIS query results.
     *
     * @see google.registry.whois.WhoisResponse
     */
    @Provides
    @Config("whoisDisclaimer")
    public static String provideWhoisDisclaimer(RegistryConfigSettings config) {
      return config.registryPolicy.whoisDisclaimer;
    }

    /**
     * Message template for whois response when queried domain is blocked by BSA.
     *
     * @see google.registry.whois.WhoisResponse
     */
    @Provides
    @Config("domainBlockedByBsaTemplate")
    public static String provideDomainBlockedByBsaTemplate(RegistryConfigSettings config) {
      return config.registryPolicy.domainBlockedByBsaTemplate;
    }

    /**
     * Maximum QPS for the Google Cloud Monitoring V3 (aka Stackdriver) API. The QPS limit can be
     * adjusted by contacting Cloud Support.
     *
     * @see com.google.monitoring.metrics.stackdriver.StackdriverWriter
     */
    @Provides
    @Config("stackdriverMaxQps")
    public static int provideStackdriverMaxQps(RegistryConfigSettings config) {
      return config.monitoring.stackdriverMaxQps;
    }

    /**
     * Maximum number of points that can be sent to Stackdriver in a single {@code
     * TimeSeries.Create} API call.
     *
     * @see com.google.monitoring.metrics.stackdriver.StackdriverWriter
     */
    @Provides
    @Config("stackdriverMaxPointsPerRequest")
    public static int provideStackdriverMaxPointsPerRequest(RegistryConfigSettings config) {
      return config.monitoring.stackdriverMaxPointsPerRequest;
    }

    /**
     * The reporting interval for {@link com.google.monitoring.metrics.Metric} instances to be sent
     * to a {@link com.google.monitoring.metrics.MetricWriter}.
     *
     * @see com.google.monitoring.metrics.MetricReporter
     */
    @Provides
    @Config("metricsWriteInterval")
    public static Duration provideMetricsWriteInterval(RegistryConfigSettings config) {
      return Duration.standardSeconds(config.monitoring.writeIntervalSeconds);
    }

    /**
     * The global automatic transfer length for contacts. After this amount of time has elapsed, the
     * transfer is automatically approved.
     *
     * @see google.registry.flows.contact.ContactTransferRequestFlow
     */
    @Provides
    @Config("contactAutomaticTransferLength")
    public static Duration provideContactAutomaticTransferLength(RegistryConfigSettings config) {
      return Duration.standardDays(config.registryPolicy.contactAutomaticTransferDays);
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
     * The server ID used in the 'svID' element of an EPP 'greeting'.
     *
     * @see <a href="https://tools.ietf.org/html/rfc5730">RFC 7530</a>
     */
    @Provides
    @Config("greetingServerId")
    public static String provideGreetingServerId(RegistryConfigSettings config) {
      return config.registryPolicy.greetingServerId;
    }

    @Provides
    @Config("activeKeyring")
    public static String provideKeyring(RegistryConfigSettings config) {
      return config.keyring.activeKeyring;
    }

    @Provides
    @Config("customLogicFactoryClass")
    public static String provideCustomLogicFactoryClass(RegistryConfigSettings config) {
      return config.registryPolicy.customLogicFactoryClass;
    }

    @Provides
    @Config("whoisCommandFactoryClass")
    public static String provideWhoisCommandFactoryClass(RegistryConfigSettings config) {
      return config.registryPolicy.whoisCommandFactoryClass;
    }

    @Provides
    @Config("allocationTokenCustomLogicClass")
    public static String provideAllocationTokenCustomLogicClass(RegistryConfigSettings config) {
      return config.registryPolicy.allocationTokenCustomLogicClass;
    }

    @Provides
    @Config("dnsCountQueryCoordinatorClass")
    public static String dnsCountQueryCoordinatorClass(RegistryConfigSettings config) {
      return config.registryPolicy.dnsCountQueryCoordinatorClass;
    }

    /** Returns the disclaimer text for the exported premium terms. */
    @Provides
    @Config("premiumTermsExportDisclaimer")
    public static String providePremiumTermsExportDisclaimer(RegistryConfigSettings config) {
      return formatComments(config.registryPolicy.premiumTermsExportDisclaimer);
    }

    /**
     * Returns the header text at the top of the reserved terms exported list.
     *
     * @see google.registry.export.ExportUtils#exportReservedTerms
     */
    @Provides
    @Config("reservedTermsExportDisclaimer")
    public static String provideReservedTermsExportDisclaimer(RegistryConfigSettings config) {
      return formatComments(config.registryPolicy.reservedTermsExportDisclaimer);
    }

    /**
     * Returns the ID of the registrar that admins are automatically logged in as if they aren't
     * otherwise associated with one.
     */
    @Provides
    @Config("registryAdminClientId")
    public static String provideRegistryAdminClientId(RegistryConfigSettings config) {
      return config.registryPolicy.registryAdminClientId;
    }

    @Singleton
    @Provides
    static RegistryConfigSettings provideRegistryConfigSettings() {
      return CONFIG_SETTINGS.get();
    }

    /**
     * Provides service account email addresses allowed to authenticate with the app at {@link
     * google.registry.request.auth.AuthSettings.AuthLevel#APP} level.
     */
    @Provides
    @Config("allowedServiceAccountEmails")
    public static ImmutableSet<String> provideAllowedServiceAccountEmails(
        RegistryConfigSettings config) {
      return ImmutableSet.copyOf(config.auth.allowedServiceAccountEmails);
    }

    @Provides
    @Config("oauthClientId")
    public static String provideOauthClientId(RegistryConfigSettings config) {
      return config.auth.oauthClientId;
    }

    /**
     * Provides the OAuth scopes required for accessing Google APIs using the default credential.
     */
    @Provides
    @Config("defaultCredentialOauthScopes")
    public static ImmutableList<String> provideServiceAccountCredentialOauthScopes(
        RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.credentialOAuth.defaultCredentialOauthScopes);
    }

    /** Provides the OAuth scopes required for delegated admin access to G Suite domain. */
    @Provides
    @Config("delegatedCredentialOauthScopes")
    public static ImmutableList<String> provideDelegatedCredentialOauthScopes(
        RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.credentialOAuth.delegatedCredentialOauthScopes);
    }

    /** Provides the OAuth scopes required for credentials created locally for the nomulus tool. */
    @Provides
    @Config("localCredentialOauthScopes")
    public static ImmutableList<String> provideLocalCredentialOauthScopes(
        RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.credentialOAuth.localCredentialOauthScopes);
    }

    @Provides
    @Config("tokenRefreshDelay")
    public static java.time.Duration provideTokenRefreshDelay(RegistryConfigSettings config) {
      return java.time.Duration.ofSeconds(config.credentialOAuth.tokenRefreshDelaySeconds);
    }

    /** OAuth client ID used by the nomulus tool. */
    @Provides
    @Config("toolsClientId")
    public static String provideToolsClientId(RegistryConfigSettings config) {
      return config.registryTool.clientId;
    }

    /** OAuth client secret used by the nomulus tool. */
    @Provides
    @Config("toolsClientSecret")
    public static String provideToolsClientSecret(RegistryConfigSettings config) {
      return config.registryTool.clientSecret;
    }

    @Provides
    @Config("rdapTos")
    public static ImmutableList<String> provideRdapTos(RegistryConfigSettings config) {
      return ImmutableList.copyOf(Splitter.on('\n').split(config.registryPolicy.rdapTos));
    }

    /**
     * Link to static Web page with RDAP terms of service. Displayed in RDAP responses.
     *
     * @see google.registry.rdap.RdapJsonFormatter
     */
    @Provides
    @Config("rdapTosStaticUrl")
    @Nullable
    public static String provideRdapTosStaticUrl(RegistryConfigSettings config) {
      return config.registryPolicy.rdapTosStaticUrl;
    }

    @Provides
    @Config("maxValidityDaysSchedule")
    public static ImmutableSortedMap<DateTime, Integer> provideValidityDaysMap(
        RegistryConfigSettings config) {
      return config.sslCertificateValidation.maxValidityDaysSchedule.entrySet().stream()
          .collect(
              toImmutableSortedMap(
                  naturalOrder(),
                  e ->
                      "START_OF_TIME".equals(e.getKey())
                          ? START_OF_TIME
                          : DateTime.parse(e.getKey()),
                  Entry::getValue));
    }

    @Provides
    @Config("expirationWarningDays")
    public static int provideExpirationWarningDays(RegistryConfigSettings config) {
      return config.sslCertificateValidation.expirationWarningDays;
    }

    @Provides
    @Config("expirationWarningIntervalDays")
    public static int provideExpirationWarningIntervalDays(RegistryConfigSettings config) {
      return config.sslCertificateValidation.expirationWarningIntervalDays;
    }

    @Provides
    @Config("minimumRsaKeyLength")
    public static int provideMinimumRsaKeyLength(RegistryConfigSettings config) {
      return config.sslCertificateValidation.minimumRsaKeyLength;
    }

    @Provides
    @Config("expirationWarningEmailBodyText")
    public static String provideExpirationWarningEmailBodyText(RegistryConfigSettings config) {
      return config.sslCertificateValidation.expirationWarningEmailBodyText;
    }

    @Provides
    @Config("expirationWarningEmailSubjectText")
    public static String provideExpirationWarningEmailSubjectText(RegistryConfigSettings config) {
      return config.sslCertificateValidation.expirationWarningEmailSubjectText;
    }

    @Provides
    @Config("dnsUpdateFailEmailSubjectText")
    public static String provideDnsUpdateFailEmailSubjectText(RegistryConfigSettings config) {
      return config.dnsUpdate.dnsUpdateFailEmailSubjectText;
    }

    @Provides
    @Config("dnsUpdateFailEmailBodyText")
    public static String provideDnsUpdateFailEmailBodyText(RegistryConfigSettings config) {
      return config.dnsUpdate.dnsUpdateFailEmailBodyText;
    }

    @Provides
    @Config("dnsUpdateFailRegistryName")
    public static String provideDnsUpdateFailRegistryName(RegistryConfigSettings config) {
      return config.dnsUpdate.dnsUpdateFailRegistryName;
    }

    @Provides
    @Config("registrySupportEmail")
    public static InternetAddress provideRegistrySupportEmail(RegistryConfigSettings config) {
      return parseEmailAddress(config.dnsUpdate.registrySupportEmail);
    }

    @Provides
    @Config("registryCcEmail")
    public static InternetAddress provideRegistryCcEmail(RegistryConfigSettings config) {
      return parseEmailAddress(config.dnsUpdate.registryCcEmail);
    }

    @Provides
    @Config("allowedEcdsaCurves")
    public static ImmutableSet<String> provideAllowedEcdsaCurves(RegistryConfigSettings config) {
      return ImmutableSet.copyOf(config.sslCertificateValidation.allowedEcdsaCurves);
    }

    @Provides
    @Config("minMonthsBeforeWipeOut")
    public static int provideMinMonthsBeforeWipeOut(RegistryConfigSettings config) {
      return config.contactHistory.minMonthsBeforeWipeOut;
    }

    @Provides
    @Config("jdbcBatchSize")
    public static int provideHibernateJdbcBatchSize(RegistryConfigSettings config) {
      return config.hibernate.jdbcBatchSize;
    }

    @Provides
    @Config("bulkPricingPackageCreateLimitEmailSubject")
    public static String provideBulkPricingPackageCreateLimitEmailSubject(
        RegistryConfigSettings config) {
      return config.bulkPricingPackageMonitoring.bulkPricingPackageCreateLimitEmailSubject;
    }

    @Provides
    @Config("bulkPricingPackageCreateLimitEmailBody")
    public static String provideBulkPricingPackageCreateLimitEmailBody(
        RegistryConfigSettings config) {
      return config.bulkPricingPackageMonitoring.bulkPricingPackageCreateLimitEmailBody;
    }

    @Provides
    @Config("bulkPricingPackageDomainLimitWarningEmailSubject")
    public static String provideBulkPricingPackageDomainLimitWarningEmailSubject(
        RegistryConfigSettings config) {
      return config.bulkPricingPackageMonitoring.bulkPricingPackageDomainLimitWarningEmailSubject;
    }

    @Provides
    @Config("bulkPricingPackageDomainLimitWarningEmailBody")
    public static String provideBulkPricingPackageDomainLimitWarningEmailBody(
        RegistryConfigSettings config) {
      return config.bulkPricingPackageMonitoring.bulkPricingPackageDomainLimitWarningEmailBody;
    }

    @Provides
    @Config("bulkPricingPackageDomainLimitUpgradeEmailSubject")
    public static String provideBulkPricingPackageDomainLimitUpgradeEmailSubject(
        RegistryConfigSettings config) {
      return config.bulkPricingPackageMonitoring.bulkPricingPackageDomainLimitUpgradeEmailSubject;
    }

    @Provides
    @Config("bulkPricingPackageDomainLimitUpgradeEmailBody")
    public static String provideBulkPricingPackageDomainLimitUpgradeEmailBody(
        RegistryConfigSettings config) {
      return config.bulkPricingPackageMonitoring.bulkPricingPackageDomainLimitUpgradeEmailBody;
    }

    @Provides
    @Config("bsaGcsBucket")
    public static String provideBsaGcsBucket(@Config("projectId") String projectId) {
      return projectId + "-bsa";
    }

    @Provides
    @Config("bsaChecksumAlgorithm")
    public static String provideBsaChecksumAlgorithm(RegistryConfigSettings config) {
      return config.bsa.bsaChecksumAlgorithm;
    }

    @Provides
    @Config("bsaLockLeaseExpiry")
    public static Duration provideBsaLockLeaseExpiry(RegistryConfigSettings config) {
      return Duration.standardMinutes(config.bsa.bsaLockLeaseExpiryMinutes);
    }

    /** Returns the desired interval between successive BSA downloads. */
    @Provides
    @Config("bsaDownloadInterval")
    public static Duration provideBsaDownloadInterval(RegistryConfigSettings config) {
      return Duration.standardMinutes(config.bsa.bsaDownloadIntervalMinutes);
    }

    /**
     * Returns the maximum period when a BSA download can be skipped due to the checksum-based
     * equality check with the previous download.
     */
    @Provides
    @Config("bsaMaxNopInterval")
    public static Duration provideBsaMaxNopInterval(RegistryConfigSettings config) {
      return Duration.standardHours(config.bsa.bsaMaxNopIntervalHours);
    }

    @Provides
    @Config("bsaTxnBatchSize")
    public static int provideBsaTxnBatchSize(RegistryConfigSettings config) {
      return config.bsa.bsaTxnBatchSize;
    }

    @Provides
    @Config("domainCreateTxnCommitTimeLag")
    public static Duration provideDomainCreateTxnCommitTimeLag(RegistryConfigSettings config) {
      return Duration.standardSeconds(config.bsa.domainCreateTxnCommitTimeLagSeconds);
    }

    @Provides
    @Config("bsaValidationMaxStaleness")
    public static Duration provideBsaValidationMaxStaleness(RegistryConfigSettings config) {
      return Duration.standardSeconds(config.bsa.bsaValidationMaxStalenessSeconds);
    }

    @Provides
    @Config("bsaAuthUrl")
    public static String provideBsaAuthUrl(RegistryConfigSettings config) {
      return config.bsa.authUrl;
    }

    @Provides
    @Config("bsaAuthTokenExpiry")
    public static Duration provideBsaAuthTokenExpiry(RegistryConfigSettings config) {
      return Duration.standardSeconds(config.bsa.authTokenExpirySeconds);
    }

    @Provides
    @Config("bsaDataUrls")
    public static ImmutableMap<String, String> provideBsaDataUrls(RegistryConfigSettings config) {
      return ImmutableMap.copyOf(config.bsa.dataUrls);
    }

    /** Provides the BSA Http endpoint for reporting order processing status. */
    @Provides
    @Config("bsaOrderStatusUrl")
    public static String provideBsaOrderStatusUrls(RegistryConfigSettings config) {
      return config.bsa.orderStatusUrl;
    }

    /** Provides the BSA Http endpoint for reporting new unblockable domains. */
    @Provides
    @Config("bsaAddUnblockableDomainsUrl")
    public static String provideBsaAddUnblockableDomainsUrls(RegistryConfigSettings config) {
      return String.format("%s?%s", config.bsa.unblockableDomainsUrl, "action=add");
    }

    /** Provides the BSA Http endpoint for reporting domains that have become blockable. */
    @Provides
    @Config("bsaRemoveUnblockableDomainsUrl")
    public static String provideBsaRemoveUnblockableDomainsUrls(RegistryConfigSettings config) {
      return String.format("%s?%s", config.bsa.unblockableDomainsUrl, "action=remove");
    }

    @Provides
    @Config("bsaUploadUnavailableDomainsUrl")
    public static String provideBsaUploadUnavailableDomainsUrl(RegistryConfigSettings config) {
      return config.bsa.uploadUnavailableDomainsUrl;
    }

    private static String formatComments(String text) {
      return Splitter.on('\n').omitEmptyStrings().trimResults().splitToList(text).stream()
          .map(s -> "# " + s)
          .collect(Collectors.joining("\n"));
    }
  }

  /** Returns the App Engine project ID, which is based off the environment name. */
  public static String getProjectId() {
    return CONFIG_SETTINGS.get().gcpProject.projectId;
  }

  /**
   * Returns the length of time before commit logs should be deleted from the database.
   *
   * @see google.registry.tools.server.GenerateZoneFilesAction
   */
  public static Duration getDatabaseRetention() {
    return Duration.standardDays(30);
  }

  public static boolean areServersLocal() {
    return CONFIG_SETTINGS.get().gcpProject.isLocal;
  }

  public static String getBaseDomain() {
    return CONFIG_SETTINGS.get().gcpProject.baseDomain;
  }

  public static URL getServiceUrl(GkeService service) {
    return makeUrl(String.format("https://%s.%s", service.getServiceId(), getBaseDomain()));
  }

  /**
   * Returns the address of the Nomulus app default HTTP server.
   *
   * <p>This is used by the {@code nomulus} tool to connect to the App Engine remote API.
   */
  public static URL getDefaultServer() {
    return makeUrl(CONFIG_SETTINGS.get().gcpProject.defaultServiceUrl);
  }

  /**
   * Returns the address of the Nomulus app backend HTTP server.
   *
   * <p>This is used by the {@code nomulus} tool to connect to the App Engine remote API.
   */
  public static URL getBackendServer() {
    return makeUrl(CONFIG_SETTINGS.get().gcpProject.backendServiceUrl);
  }

  /**
   * Returns the address of the Nomulus app bsa HTTP server.
   *
   * <p>This is used by the {@code nomulus} tool to connect to the App Engine remote API.
   */
  public static URL getBsaServer() {
    return makeUrl(CONFIG_SETTINGS.get().gcpProject.bsaServiceUrl);
  }

  /**
   * Returns the address of the Nomulus app tools HTTP server.
   *
   * <p>This is used by the {@code nomulus} tool to connect to the App Engine remote API.
   */
  public static URL getToolsServer() {
    return makeUrl(CONFIG_SETTINGS.get().gcpProject.toolsServiceUrl);
  }

  /**
   * Returns the address of the Nomulus app pubapi HTTP server.
   *
   * <p>This is used by the {@code nomulus} tool to connect to the App Engine remote API.
   */
  public static URL getPubapiServer() {
    return makeUrl(CONFIG_SETTINGS.get().gcpProject.pubapiServiceUrl);
  }

  /** Returns the amount of time a singleton should be cached, before expiring. */
  public static java.time.Duration getSingletonCacheRefreshDuration() {
    return java.time.Duration.ofSeconds(CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds);
  }

  /**
   * Returns the amount of time a domain label list should be cached in memory before expiring.
   *
   * @see google.registry.model.tld.label.ReservedList
   * @see google.registry.model.tld.label.PremiumList
   */
  public static java.time.Duration getDomainLabelListCacheDuration() {
    return java.time.Duration.ofSeconds(CONFIG_SETTINGS.get().caching.domainLabelCachingSeconds);
  }

  /** Returns the amount of time a singleton should be cached in persist mode, before expiring. */
  public static java.time.Duration getSingletonCachePersistDuration() {
    return java.time.Duration.ofSeconds(CONFIG_SETTINGS.get().caching.singletonCachePersistSeconds);
  }

  /**
   * Returns the maximum number of premium list entries across all TLDs to keep in in-memory cache.
   */
  public static int getStaticPremiumListMaxCachedEntries() {
    return CONFIG_SETTINGS.get().caching.staticPremiumListMaxCachedEntries;
  }

  public static boolean isEppResourceCachingEnabled() {
    return CONFIG_SETTINGS.get().caching.eppResourceCachingEnabled;
  }

  @VisibleForTesting
  public static void overrideIsEppResourceCachingEnabledForTesting(boolean enabled) {
    CONFIG_SETTINGS.get().caching.eppResourceCachingEnabled = enabled;
  }

  /**
   * Returns the amount of time an EPP resource or key should be cached in memory before expiring.
   */
  public static java.time.Duration getEppResourceCachingDuration() {
    return java.time.Duration.ofSeconds(CONFIG_SETTINGS.get().caching.eppResourceCachingSeconds);
  }

  /** Returns the maximum number of EPP resources and keys to keep in in-memory cache. */
  public static int getEppResourceMaxCachedEntries() {
    return CONFIG_SETTINGS.get().caching.eppResourceMaxCachedEntries;
  }

  /** Returns the amount of time that a particular claims list should be cached. */
  public static java.time.Duration getClaimsListCacheDuration() {
    return java.time.Duration.ofSeconds(CONFIG_SETTINGS.get().caching.claimsListCachingSeconds);
  }

  /** Returns the email address that outgoing emails from the app are sent from. */
  public static InternetAddress getGSuiteOutgoingEmailAddress() {
    return parseEmailAddress(CONFIG_SETTINGS.get().gSuite.outgoingEmailAddress);
  }

  /** Returns the display name that outgoing emails from the app are sent from. */
  public static String getGSuiteOutgoingEmailDisplayName() {
    return CONFIG_SETTINGS.get().gSuite.outgoingEmailDisplayName;
  }

  /**
   * Returns default WHOIS server to use when {@code Registrar#getWhoisServer()} is {@code null}.
   *
   * @see "google.registry.whois.DomainWhoisResponse"
   * @see "google.registry.whois.RegistrarWhoisResponse"
   */
  public static String getDefaultRegistrarWhoisServer() {
    return CONFIG_SETTINGS.get().registryPolicy.defaultRegistrarWhoisServer;
  }

  /** Returns the default database transaction isolation. */
  public static String getHibernateConnectionIsolation() {
    return CONFIG_SETTINGS.get().hibernate.connectionIsolation;
  }

  /** Returns true if nested calls to {@code tm().transact()} are allowed. */
  public static boolean getHibernateAllowNestedTransactions() {
    return CONFIG_SETTINGS.get().hibernate.allowNestedTransactions;
  }

  /** Returns true if hibernate.show_sql is enabled. */
  public static String getHibernateLogSqlQueries() {
    return CONFIG_SETTINGS.get().hibernate.logSqlQueries;
  }

  /** Returns the connection timeout for HikariCP. */
  public static String getHibernateHikariConnectionTimeout() {
    return CONFIG_SETTINGS.get().hibernate.hikariConnectionTimeout;
  }

  /** Returns the minimum idle connections for HikariCP. */
  public static String getHibernateHikariMinimumIdle() {
    return CONFIG_SETTINGS.get().hibernate.hikariMinimumIdle;
  }

  /** Returns the maximum pool size for HikariCP. */
  public static String getHibernateHikariMaximumPoolSize() {
    return CONFIG_SETTINGS.get().hibernate.hikariMaximumPoolSize;
  }

  /** Returns the idle timeout for HikariCP. */
  public static String getHibernateHikariIdleTimeout() {
    return CONFIG_SETTINGS.get().hibernate.hikariIdleTimeout;
  }

  /**
   * JDBC-specific: driver default batch size is 0, which means that every INSERT statement will be
   * sent to the database individually. Batching allows us to group together multiple inserts into
   * one single INSERT statement which can dramatically increase speed in situations with many
   * inserts.
   *
   * <p><a
   * href="https://docs.jboss.org/hibernate/orm/5.6/userguide/html_single/Hibernate_User_Guide.html">Hibernate
   * User Guide</a> recommends between 10 and 50.
   */
  public static int getHibernateJdbcBatchSize() {
    return CONFIG_SETTINGS.get().hibernate.jdbcBatchSize;
  }

  /**
   * Returns the JDBC fetch size.
   *
   * <p>Postgresql-specific: driver default fetch size is 0, which disables streaming result sets.
   * Here we set a small default geared toward Nomulus server transactions. Large queries can
   * override the defaults using {@link JpaTransactionManager#setQueryFetchSize}.
   */
  public static String getHibernateJdbcFetchSize() {
    return CONFIG_SETTINGS.get().hibernate.jdbcFetchSize;
  }

  /** Returns the roid suffix to be used for the roids of all contacts and hosts. */
  public static String getContactAndHostRoidSuffix() {
    return CONFIG_SETTINGS.get().registryPolicy.contactAndHostRoidSuffix;
  }

  /** Returns the global automatic transfer length for contacts. */
  public static Duration getContactAutomaticTransferLength() {
    return Duration.standardDays(CONFIG_SETTINGS.get().registryPolicy.contactAutomaticTransferDays);
  }

  /** A discount for all sunrise domain creates, between 0.0 (no discount) and 1.0 (free). */
  public static double getSunriseDomainCreateDiscount() {
    return CONFIG_SETTINGS.get().registryPolicy.sunriseDomainCreateDiscount;
  }

  /**
   * List of registrars for which we include a promotional price on domain checks if configured.
   *
   * <p>In these cases, when a default promotion is running for the domain+registrar combination in
   * question (a DEFAULT_PROMO token is set on the TLD), the standard non-promotional price will be
   * returned for that domain as the standard create price. We will then add an additional fee check
   * response with the actual promotional price and a "STANDARD PROMOTION" class.
   */
  public static ImmutableSet<String> getTieredPricingPromotionRegistrarIds() {
    return ImmutableSet.copyOf(
        CONFIG_SETTINGS.get().registryPolicy.tieredPricingPromotionRegistrarIds);
  }

  /**
   * If the registry stores the minimum data set according to the ICANN Registration Data Policy.
   *
   * <p>See <a href=https://www.icann.org/resources/pages/registration-data-policy-2024-02-21-en>the
   * ICANN policy</a> for more details. One significant change that this indicates is that the
   * registry will not require contact information on domain creations. Eventually, contact
   * information will be forbidden if the registry uses the minimum data set.
   */
  public static boolean useMinimumDataset() {
    return CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset;
  }

  /**
   * Memoizes loading of the {@link RegistryConfigSettings} POJO.
   *
   * <p>Memoizing without cache expiration is used because the app must be re-deployed in order to
   * change the contents of the YAML config files.
   */
  @VisibleForTesting
  public static final Supplier<RegistryConfigSettings> CONFIG_SETTINGS =
      memoize(RegistryConfig::getConfigSettings);

  private static InternetAddress parseEmailAddress(String email) {
    try {
      return new InternetAddress(email);
    } catch (AddressException e) {
      throw new IllegalArgumentException(String.format("Could not parse email address %s.", email));
    }
  }

  private RegistryConfig() {}
}
