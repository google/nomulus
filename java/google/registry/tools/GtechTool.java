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

package google.registry.tools;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.tools.Command.GtechCommand;

/** Command line interface with a subset of commands that are safe for tech support to run. */
public final class GtechTool {

  /**
   * Commands that exist in both {@link GtechTool} and {@link RegistryTool}.
   *
   * <p><b>Note:</b> If changing the command-line name of any commands below, remember to resolve
   * any invocations in scripts (e.g. PDT, ICANN reporting).
   */
  static final ImmutableMap<String, Class<? extends GtechCommand>> COMMAND_MAP =
      ImmutableSortedMap.<String, Class<? extends GtechCommand>>naturalOrder()
          .put("auction_status", AuctionStatusCommand.class)
          .put("canonicalize_labels", CanonicalizeLabelsCommand.class)
          .put("convert_idn", ConvertIdnCommand.class)
          .put("create_anchor_tenant", CreateAnchorTenantCommand.class)
          .put("create_contact", CreateContactCommand.class)
          .put("create_credit", CreateCreditCommand.class)
          .put("create_credit_balance", CreateCreditBalanceCommand.class)
          .put("create_domain", CreateDomainCommand.class)
          .put("create_host", CreateHostCommand.class)
          .put("create_registrar_groups", CreateRegistrarGroupsCommand.class)
          .put("create_registrar", CreateRegistrarCommand.class)
          .put("create_sandbox_tld", CreateSandboxTldCommand.class)
          .put("delete_domain", DeleteDomainCommand.class)
          .put("domain_application_info", DomainApplicationInfoCommand.class)
          .put("domain_check", DomainCheckCommand.class)
          .put("domain_check_claims", DomainCheckClaimsCommand.class)
          .put("domain_check_fee", DomainCheckFeeCommand.class)
          .put("generate_applications_report", GenerateApplicationsReportCommand.class)
          .put("generate_auction_data", GenerateAuctionDataCommand.class)
          .put("generate_dns_report", GenerateDnsReportCommand.class)
          .put("get_application", GetApplicationCommand.class)
          .put("get_application_ids", GetApplicationIdsCommand.class)
          .put("get_applied_labels", GetAppliedLabelsCommand.class)
          .put("get_contact", GetContactCommand.class)
          .put("get_domain", GetDomainCommand.class)
          .put("get_lrp_token", GetLrpTokenCommand.class)
          .put("get_history_entries", GetHistoryEntriesCommand.class)
          .put("get_host", GetHostCommand.class)
          .put("get_registrar", GetRegistrarCommand.class)
          .put("get_schema", GetSchemaCommand.class)
          .put("get_schema_tree", GetSchemaTreeCommand.class)
          .put("get_tld", GetTldCommand.class)
          .put("hash_certificate", HashCertificateCommand.class)
          .put("list_credits", ListCreditsCommand.class)
          .put("list_registrars", ListRegistrarsCommand.class)
          .put("list_tlds", ListTldsCommand.class)
          .put("publish_detail_report", PublishDetailReportCommand.class)
          .put("registrar_activity_report", RegistrarActivityReportCommand.class)
          .put("registrar_contact", RegistrarContactCommand.class)
          .put("setup_ote", SetupOteCommand.class)
          .put("uniform_rapid_suspension", UniformRapidSuspensionCommand.class)
          .put("update_registrar", UpdateRegistrarCommand.class)
          .put("update_sandbox_tld", UpdateSandboxTldCommand.class)
          .put("update_server_locks", UpdateServerLocksCommand.class)
          .put("validate_login_credentials", ValidateLoginCredentialsCommand.class)
          .put("verify_ote", VerifyOteCommand.class)
          .put("whois_query", WhoisQueryCommand.class)
          .build();

  public static void main(String[] args) throws Exception {
    RegistryToolEnvironment.parseFromArgs(args).setup();
    new RegistryCli().run("gtech_tool", args, COMMAND_MAP);
  }
}
