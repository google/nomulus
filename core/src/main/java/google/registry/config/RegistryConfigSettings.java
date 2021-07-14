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

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The POJO that YAML config files are deserialized into. */
public class RegistryConfigSettings {

  public AppEngine appEngine;
  public GSuite gSuite;
  public OAuth oAuth;
  public CredentialOAuth credentialOAuth;
  public RegistryPolicy registryPolicy;
  public Datastore datastore;
  public Hibernate hibernate;
  public CloudSql cloudSql;
  public CloudDns cloudDns;
  public Caching caching;
  public IcannReporting icannReporting;
  public Billing billing;
  public Rde rde;
  public RegistrarConsole registrarConsole;
  public Monitoring monitoring;
  public Misc misc;
  public Beam beam;
  public Keyring keyring;
  public RegistryTool registryTool;
  public SslCertificateValidation sslCertificateValidation;

  /** Configuration options that apply to the entire App Engine project. */
  public static class AppEngine {
    public String projectId;
    public boolean isLocal;
    public String defaultServiceUrl;
    public String backendServiceUrl;
    public String toolsServiceUrl;
    public String pubapiServiceUrl;
  }

  /** Configuration options for OAuth settings for authenticating users. */
  public static class OAuth {
    public List<String> availableOauthScopes;
    public List<String> requiredOauthScopes;
    public List<String> allowedOauthClientIds;
  }

  /** Configuration options for accessing Google APIs. */
  public static class CredentialOAuth {
    public List<String> defaultCredentialOauthScopes;
    public List<String> delegatedCredentialOauthScopes;
    public List<String> localCredentialOauthScopes;
  }

  /** Configuration options for the G Suite account used by Nomulus. */
  public static class GSuite {
    public String domainName;
    public String outgoingEmailAddress;
    public String outgoingEmailDisplayName;
    public String adminAccountEmailAddress;
    public String supportGroupEmailAddress;
  }

  /** Configuration options for registry policy. */
  public static class RegistryPolicy {
    public String contactAndHostRoidSuffix;
    public String productName;
    public String customLogicFactoryClass;
    public String whoisCommandFactoryClass;
    public String allocationTokenCustomLogicClass;
    public String dnsCountQueryCoordinatorClass;
    public int contactAutomaticTransferDays;
    public String greetingServerId;
    public List<String> registrarChangesNotificationEmailAddresses;
    public String defaultRegistrarWhoisServer;
    public String tmchCaMode;
    public String tmchCrlUrl;
    public String tmchMarksDbUrl;
    public String checkApiServletClientId;
    public String registryAdminClientId;
    public String premiumTermsExportDisclaimer;
    public String reservedTermsExportDisclaimer;
    public String whoisRedactedEmailText;
    public String whoisDisclaimer;
    public String rdapTos;
    public String rdapTosStaticUrl;
    public String registryName;
    public List<String> spec11WebResources;
    public boolean requireSslCertificates;
  }

  /** Configuration for Cloud Datastore. */
  public static class Datastore {
    public int commitLogBucketsNum;
    public int eppResourceIndexBucketsNum;
    public int baseOfyRetryMillis;
  }

  /** Configuration for Hibernate. */
  public static class Hibernate {
    public String connectionIsolation;
    public String logSqlQueries;
    public String hikariConnectionTimeout;
    public String hikariMinimumIdle;
    public String hikariMaximumPoolSize;
    public String hikariIdleTimeout;
  }

  /** Configuration for Cloud SQL. */
  public static class CloudSql {
    public String jdbcUrl;
    // TODO(05012021): remove username field after it is removed from all yaml files.
    public String username;
    public String instanceConnectionName;
  }

  /** Configuration for Apache Beam (Cloud Dataflow). */
  public static class Beam {
    public String defaultJobRegion;
    public String stagingBucketUrl;
  }

  /** Configuration for Cloud DNS. */
  public static class CloudDns {
    public String rootUrl;
    public String servicePath;
  }

  /** Configuration for caching. */
  public static class Caching {
    public int singletonCacheRefreshSeconds;
    public int domainLabelCachingSeconds;
    public int singletonCachePersistSeconds;
    public int staticPremiumListMaxCachedEntries;
    public boolean eppResourceCachingEnabled;
    public int eppResourceCachingSeconds;
    public int eppResourceMaxCachedEntries;
  }

  /** Configuration for ICANN monthly reporting. */
  public static class IcannReporting {
    public String icannTransactionsReportingUploadUrl;
    public String icannActivityReportingUploadUrl;
  }

  /** Configuration for monthly invoices. */
  public static class Billing {
    public List<String> invoiceEmailRecipients;
    public String invoiceFilePrefix;
  }

  /** Configuration for Registry Data Escrow (RDE). */
  public static class Rde {
    public String reportUrlPrefix;
    public String uploadUrl;
    public String sshIdentityEmailAddress;
  }

  /** Configuration for the web-based registrar console. */
  public static class RegistrarConsole {
    public String logoFilename;
    public String supportPhoneNumber;
    public String supportEmailAddress;
    public String announcementsEmailAddress;
    public String integrationEmailAddress;
    public String technicalDocsUrl;
    public AnalyticsConfig analyticsConfig;
  }

  /** Configuration for analytics services installed in the registrar console */
  public static class AnalyticsConfig {
    public String googleAnalyticsId;
  }

  /** Configuration for monitoring. */
  public static class Monitoring {
    public int stackdriverMaxQps;
    public int stackdriverMaxPointsPerRequest;
    public int writeIntervalSeconds;
  }

  /** Miscellaneous configuration that doesn't quite fit in anywhere else. */
  public static class Misc {
    public String sheetExportId;
    public String alertRecipientEmailAddress;
    public String spec11OutgoingEmailAddress;
    public List<String> spec11BccEmailAddresses;
    public int asyncDeleteDelaySeconds;
    public int transientFailureRetries;
  }

  /** Configuration for keyrings (used to store secrets outside of source). */
  public static class Keyring {
    public String activeKeyring;
    public Kms kms;
  }

  /** Configuration for Cloud KMS. */
  public static class Kms {
    public String keyringName;
    public String projectId;
  }

  /** Configuration options for the registry tool. */
  public static class RegistryTool {
    public String clientId;
    public String clientSecret;
    // TODO(05012021): remove username field after it is removed from all yaml files.
    public String username;
  }

  /** Configuration for the certificate checker. */
  public static class SslCertificateValidation {
    public Map<String, Integer> maxValidityDaysSchedule;
    public int expirationWarningDays;
    public int minimumRsaKeyLength;
    public Set<String> allowedEcdsaCurves;
  }
}
