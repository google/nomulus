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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

/** Command line interface with a subset of commands that are safe for tech support to run. */
public final class GtechTool {

  /** Names of commands from {@link RegistryTool#COMMAND_MAP} to include in GtechTool. */
  @VisibleForTesting
  static final ImmutableSet<String> COMMAND_SET = ImmutableSet.of(
      "canonicalize_labels",
      "check_domain",
      "check_domain_claims",
      "convert_idn",
      "count_domains",
      "create_anchor_tenant",
      "create_contact",
      "create_domain",
      "create_host",
      "create_registrar",
      "create_registrar_groups",
      "create_sandbox_tld",
      "delete_domain",
      "generate_dns_report",
      "get_allocation_token",
      "get_claims_list",
      "get_contact",
      "get_domain",
      "get_history_entries",
      "get_host",
      "get_registrar",
      "get_routing_map",
      "get_schema",
      "get_schema_tree",
      "get_tld",
      "hash_certificate",
      "list_registrars",
      "list_tlds",
      "lock_domain",
      "login",
      "logout",
      "registrar_contact",
      "setup_ote",
      "uniform_rapid_suspension",
      "unlock_domain",
      "unrenew_domain",
      "update_domain",
      "update_registrar",
      "update_sandbox_tld",
      "update_server_locks",
      "validate_login_credentials",
      "verify_ote",
      "whois_query");

  @VisibleForTesting
  static final ImmutableMap<String, Class<? extends Command>> COMMAND_MAP =
      ImmutableMap.copyOf(Maps.filterKeys(RegistryTool.COMMAND_MAP, Predicates.in(COMMAND_SET)));

  public static void main(String[] args) throws Exception {
    RegistryToolEnvironment.parseFromArgs(args).setup();
    try (RegistryCli cli = new RegistryCli("gtech_tool", COMMAND_MAP)) {
      cli.run(args);
    }
  }
}
