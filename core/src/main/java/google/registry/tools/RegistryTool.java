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

package google.registry.tools;

import com.google.common.collect.ImmutableMap;
import google.registry.tools.javascrap.CreateCancellationsForBillingEventsCommand;
import google.registry.tools.javascrap.RecreateBillingRecurrencesCommand;

/** Container class to create and run remote commands against a server instance. */
public final class RegistryTool {

  /**
   * Available commands.
   *
   * <p><b>Note:</b> If changing the command-line name of any commands below, remember to resolve
   * any invocations in scripts (e.g. PDT, ICANN reporting).
   */
  public static final ImmutableMap<String, Class<? extends Command>> COMMAND_MAP =
      new ImmutableMap.Builder<String, Class<? extends Command>>()
          .put("ack_poll_messages", AckPollMessagesCommand.class)
          .put("canonicalize_labels", CanonicalizeLabelsCommand.class)
          .put("check_domain", CheckDomainCommand.class)
          .put("check_domain_claims", CheckDomainClaimsCommand.class)
          .put("configure_feature_flag", ConfigureFeatureFlagCommand.class)
          .put("configure_tld", ConfigureTldCommand.class)
          .put("convert_idn", ConvertIdnCommand.class)
          .put("count_domains", CountDomainsCommand.class)
          .put("create_anchor_tenant", CreateAnchorTenantCommand.class)
          .put("create_bulk_pricing_package", CreateBulkPricingPackageCommand.class)
          .put(
              "create_cancellations_for_billing_events",
              CreateCancellationsForBillingEventsCommand.class)
          .put("create_cdns_tld", CreateCdnsTld.class)
          .put("create_contact", CreateContactCommand.class)
          .put("create_domain", CreateDomainCommand.class)
          .put("create_host", CreateHostCommand.class)
          .put("create_premium_list", CreatePremiumListCommand.class)
          .put("create_registrar", CreateRegistrarCommand.class)
          .put("create_registrar_groups", CreateRegistrarGroupsCommand.class)
          .put("create_reserved_list", CreateReservedListCommand.class)
          .put("create_user", CreateUserCommand.class)
          .put("curl", CurlCommand.class)
          .put("delete_allocation_tokens", DeleteAllocationTokensCommand.class)
          .put("delete_domain", DeleteDomainCommand.class)
          .put("delete_host", DeleteHostCommand.class)
          .put("delete_premium_list", DeletePremiumListCommand.class)
          .put("delete_reserved_list", DeleteReservedListCommand.class)
          .put("delete_tld", DeleteTldCommand.class)
          .put("delete_user", DeleteUserCommand.class)
          .put("encrypt_escrow_deposit", EncryptEscrowDepositCommand.class)
          .put("enqueue_poll_message", EnqueuePollMessageCommand.class)
          .put("execute_epp", ExecuteEppCommand.class)
          .put("generate_allocation_tokens", GenerateAllocationTokensCommand.class)
          .put("generate_dns_report", GenerateDnsReportCommand.class)
          .put("generate_escrow_deposit", GenerateEscrowDepositCommand.class)
          .put("generate_lordn", GenerateLordnCommand.class)
          .put("generate_zone_files", GenerateZoneFilesCommand.class)
          .put("get_allocation_token", GetAllocationTokenCommand.class)
          .put("get_bulk_pricing_package", GetBulkPricingPackageCommand.class)
          .put("get_claims_list", GetClaimsListCommand.class)
          .put("get_contact", GetContactCommand.class)
          .put("get_domain", GetDomainCommand.class)
          .put("get_feature_flag", GetFeatureFlagCommand.class)
          .put("get_history_entries", GetHistoryEntriesCommand.class)
          .put("get_host", GetHostCommand.class)
          .put("get_keyring_secret", GetKeyringSecretCommand.class)
          .put("get_premium_list", GetPremiumListCommand.class)
          .put("get_registrar", GetRegistrarCommand.class)
          .put("get_reserved_list", GetReservedListCommand.class)
          .put("get_routing_map", GetRoutingMapCommand.class)
          .put("get_sql_credential", GetSqlCredentialCommand.class)
          .put("get_tld", GetTldCommand.class)
          .put("get_user", GetUserCommand.class)
          .put("ghostryde", GhostrydeCommand.class)
          .put("hash_certificate", HashCertificateCommand.class)
          .put("list_cursors", ListCursorsCommand.class)
          .put("list_domains", ListDomainsCommand.class)
          .put("list_feature_flags", ListFeatureFlagsCommand.class)
          .put("list_hosts", ListHostsCommand.class)
          .put("list_premium_lists", ListPremiumListsCommand.class)
          .put("list_registrars", ListRegistrarsCommand.class)
          .put("list_reserved_lists", ListReservedListsCommand.class)
          .put("list_tlds", ListTldsCommand.class)
          .put("load_test", LoadTestCommand.class)
          .put("lock_domain", LockDomainCommand.class)
          .put("login", LoginCommand.class)
          .put("logout", LogoutCommand.class)
          .put("pending_escrow", PendingEscrowCommand.class)
          .put("recreate_billing_recurrences", RecreateBillingRecurrencesCommand.class)
          .put("registrar_poc", RegistrarPocCommand.class)
          .put("renew_domain", RenewDomainCommand.class)
          .put("save_sql_credential", SaveSqlCredentialCommand.class)
          .put("send_escrow_report_to_icann", SendEscrowReportToIcannCommand.class)
          .put("setup_ote", SetupOteCommand.class)
          .put("uniform_rapid_suspension", UniformRapidSuspensionCommand.class)
          .put("unlock_domain", UnlockDomainCommand.class)
          .put("unrenew_domain", UnrenewDomainCommand.class)
          .put("update_allocation_tokens", UpdateAllocationTokensCommand.class)
          .put("update_bulk_pricing_package", UpdateBulkPricingPackageCommand.class)
          .put("update_cursors", UpdateCursorsCommand.class)
          .put("update_domain", UpdateDomainCommand.class)
          .put("update_keyring_secret", UpdateKeyringSecretCommand.class)
          .put("update_premium_list", UpdatePremiumListCommand.class)
          .put("update_recurrence", UpdateRecurrenceCommand.class)
          .put("update_registrar", UpdateRegistrarCommand.class)
          .put("update_reserved_list", UpdateReservedListCommand.class)
          .put("update_server_locks", UpdateServerLocksCommand.class)
          .put("update_user", UpdateUserCommand.class)
          .put("upload_claims_list", UploadClaimsListCommand.class)
          .put("validate_escrow_deposit", ValidateEscrowDepositCommand.class)
          .put("validate_login_credentials", ValidateLoginCredentialsCommand.class)
          .put("verify_ote", VerifyOteCommand.class)
          .build();

  public static void main(String[] args) throws Exception {
    RegistryToolEnvironment.parseFromArgs(args).setup();
    RegistryCli cli = new RegistryCli("nomulus", COMMAND_MAP);
    cli.run(args);
  }
}
