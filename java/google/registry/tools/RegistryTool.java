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

package google.registry.tools;

import com.google.common.collect.ImmutableMap;
import google.registry.tools.javascrap.RemoveIpAddressCommand;

/** Container class to create and run remote commands against a datastore instance. */
public final class RegistryTool {

  /**
   * Available commands.
   *
   * <p><b>Note:</b> If changing the command-line name of any commands below, remember to resolve
   * any invocations in scripts (e.g. PDT, ICANN reporting).
   */
  public static final ImmutableMap<String, Class<? extends Command>> COMMAND_MAP =
      new ImmutableMap.Builder<String, Class<? extends Command>>()
          .put("allocate_domain", AllocateDomainCommand.class)
          .put("auction_status", AuctionStatusCommand.class)
          .put("canonicalize_labels", CanonicalizeLabelsCommand.class)
          .put("check_snapshot", CheckSnapshotCommand.class)
          .put("convert_idn", ConvertIdnCommand.class)
          .put("create_anchor_tenant", CreateAnchorTenantCommand.class)
          .put("create_auction_credits", CreateAuctionCreditsCommand.class)
          .put("create_contact", CreateContactCommand.class)
          .put("create_credit", CreateCreditCommand.class)
          .put("create_credit_balance", CreateCreditBalanceCommand.class)
          .put("create_domain", CreateDomainCommand.class)
          .put("create_host", CreateHostCommand.class)
          .put("create_lrp_tokens", CreateLrpTokensCommand.class)
          .put("create_premium_list", CreatePremiumListCommand.class)
          .put("create_registrar", CreateRegistrarCommand.class)
          .put("create_registrar_groups", CreateRegistrarGroupsCommand.class)
          .put("create_reserved_list", CreateReservedListCommand.class)
          .put("create_tld", CreateTldCommand.class)
          .put("delete_credit", DeleteCreditCommand.class)
          .put("delete_domain", DeleteDomainCommand.class)
          .put("delete_entity", DeleteEntityCommand.class)
          .put("delete_epp_resource", DeleteEppResourceCommand.class)
          .put("delete_premium_list", DeletePremiumListCommand.class)
          .put("delete_reserved_list", DeleteReservedListCommand.class)
          .put("domain_application_info", DomainApplicationInfoCommand.class)
          .put("domain_check", DomainCheckCommand.class)
          .put("domain_check_claims", DomainCheckClaimsCommand.class)
          .put("domain_check_fee", DomainCheckFeeCommand.class)
          .put("encrypt_escrow_deposit", EncryptEscrowDepositCommand.class)
          .put("execute_epp", ExecuteEppCommand.class)
          .put("generate_applications_report", GenerateApplicationsReportCommand.class)
          .put("generate_auction_data", GenerateAuctionDataCommand.class)
          .put("generate_dns_report", GenerateDnsReportCommand.class)
          .put("generate_escrow_deposit", GenerateEscrowDepositCommand.class)
          .put("generate_lordn", GenerateLordnCommand.class)
          .put("generate_zone_files", GenerateZoneFilesCommand.class)
          .put("get_application", GetApplicationCommand.class)
          .put("get_application_ids", GetApplicationIdsCommand.class)
          .put("get_applied_labels", GetAppliedLabelsCommand.class)
          .put("get_claims_list", GetClaimsListCommand.class)
          .put("get_contact", GetContactCommand.class)
          .put("get_domain", GetDomainCommand.class)
          .put("get_history_entries", GetHistoryEntriesCommand.class)
          .put("get_host", GetHostCommand.class)
          .put("get_lrp_token", GetLrpTokenCommand.class)
          .put("get_registrar", GetRegistrarCommand.class)
          .put("get_resource_by_key", GetResourceByKeyCommand.class)
          .put("get_schema", GetSchemaCommand.class)
          .put("get_schema_tree", GetSchemaTreeCommand.class)
          .put("get_tld", GetTldCommand.class)
          .put("ghostryde", GhostrydeCommand.class)
          .put("hash_certificate", HashCertificateCommand.class)
          .put("list_credits", ListCreditsCommand.class)
          .put("list_cursors", ListCursorsCommand.class)
          .put("list_domains", ListDomainsCommand.class)
          .put("list_hosts", ListHostsCommand.class)
          .put("list_premium_lists", ListPremiumListsCommand.class)
          .put("list_registrars", ListRegistrarsCommand.class)
          .put("list_reserved_lists", ListReservedListsCommand.class)
          .put("list_tlds", ListTldsCommand.class)
          .put("load_snapshot", LoadSnapshotCommand.class)
          .put("make_billing_tables", MakeBillingTablesCommand.class)
          .put("pending_escrow", PendingEscrowCommand.class)
          .put("publish_detail_report", PublishDetailReportCommand.class)
          .put("registrar_activity_report", RegistrarActivityReportCommand.class)
          .put("registrar_contact", RegistrarContactCommand.class)
          .put("remove_ip_address", RemoveIpAddressCommand.class)
          .put("resave_entities", ResaveEntitiesCommand.class)
          .put("resave_environment_entities", ResaveEnvironmentEntitiesCommand.class)
          .put("resave_epp_resource", ResaveEppResourceCommand.class)
          .put("send_escrow_report_to_icann", SendEscrowReportToIcannCommand.class)
          .put("setup_ote", SetupOteCommand.class)
          .put("uniform_rapid_suspension", UniformRapidSuspensionCommand.class)
          .put("update_application_status", UpdateApplicationStatusCommand.class)
          .put("update_claims_notice", UpdateClaimsNoticeCommand.class)
          .put("update_credits", UpdateCreditsCommand.class)
          .put("update_cursors", UpdateCursorsCommand.class)
          .put("update_premium_list", UpdatePremiumListCommand.class)
          .put("update_registrar", UpdateRegistrarCommand.class)
          .put("update_reserved_list", UpdateReservedListCommand.class)
          .put("update_server_locks", UpdateServerLocksCommand.class)
          .put("update_smd", UpdateSmdCommand.class)
          .put("update_tld", UpdateTldCommand.class)
          .put("upload_claims_list", UploadClaimsListCommand.class)
          .put("validate_escrow_deposit", ValidateEscrowDepositCommand.class)
          .put("validate_login_credentials", ValidateLoginCredentialsCommand.class)
          .put("verify_ote", VerifyOteCommand.class)
          .put("whois_query", WhoisQueryCommand.class)
          .build();

  public static void main(String[] args) throws Exception {
    RegistryToolEnvironment.parseFromArgs(args).setup();
    new RegistryCli().run("nomulus", args, COMMAND_MAP);
  }
}
