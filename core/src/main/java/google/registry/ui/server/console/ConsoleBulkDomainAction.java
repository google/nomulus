// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.Expose;
import google.registry.flows.EppController;
import google.registry.flows.EppRequestSource;
import google.registry.flows.PasswordOnlyTransportCredentials;
import google.registry.flows.StatelessRequestSessionMetadata;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.Result;
import google.registry.request.Action;
import google.registry.request.OptionalJsonPayload;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Console endpoint to perform the same action to a list of domains.
 *
 * <p>All requests must include the {@link BulkAction} to perform as well as a {@link
 * BulkDomainList} of domains on which to apply the action. The remaining contents of the request
 * body depend on the type of action -- some requests may require more data than others.
 */
@Action(
    service = Action.GaeService.DEFAULT,
    gkeService = Action.GkeService.CONSOLE,
    path = ConsoleBulkDomainAction.PATH,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleBulkDomainAction extends ConsoleApiAction {

  public static final String PATH = "/console-api/bulk-domain";

  public enum BulkAction {
    DELETE,
    SUSPEND
  }

  /** All requests must include at least a list of domain names on which to perform the action. */
  public record BulkDomainList(@Expose List<String> domainList) {}

  public record BulkDomainDeleteRequest(@Expose String reason) {}

  public record BulkDomainSuspendRequest(@Expose String reason) {}

  private static final String DOMAIN_DELETE_XML =
      """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
  <command>
    <delete>
      <domain:delete
       xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
        <domain:name>%DOMAIN_NAME%</domain:name>
      </domain:delete>
    </delete>
    <extension>
      <metadata:metadata xmlns:metadata="urn:google:params:xml:ns:metadata-1.0">
        <metadata:reason>%REASON%</metadata:reason>
        <metadata:requestedByRegistrar>true</metadata:requestedByRegistrar>
      </metadata:metadata>
    </extension>
    <clTRID>RegistryConsole</clTRID>
  </command>
</epp>""";

  private static final String DOMAIN_SUSPEND_XML =
      """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<epp
  xmlns="urn:ietf:params:xml:ns:epp-1.0">
  <command>
    <update>
      <domain:update
        xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
        <domain:name>%DOMAIN_NAME%</domain:name>
        <domain:add>
          <domain:status s="serverDeleteProhibited" lang="en"></domain:status>
          <domain:status s="serverHold" lang="en"></domain:status>
          <domain:status s="serverRenewProhibited" lang="en"></domain:status>
          <domain:status s="serverTransferProhibited" lang="en"></domain:status>
          <domain:status s="serverUpdateProhibited" lang="en"></domain:status>
        </domain:add>
        <domain:rem></domain:rem>
      </domain:update>
    </update>
    <extension>
      <metadata:metadata
        xmlns:metadata="urn:google:params:xml:ns:metadata-1.0">
        <metadata:reason>Console suspension: %REASON%</metadata:reason>
        <metadata:requestedByRegistrar>false</metadata:requestedByRegistrar>
      </metadata:metadata>
    </extension>
    <clTRID>RegistryTool</clTRID>
  </command>
</epp>""";

  private final Gson gson;
  private final EppController eppController;
  private final String registrarId;
  private final String bulkDomainAction;
  private final Optional<JsonElement> optionalJsonPayload;

  @Inject
  public ConsoleBulkDomainAction(
      ConsoleApiParams consoleApiParams,
      Gson gson,
      EppController eppController,
      @Parameter("registrarId") String registrarId,
      @Parameter("bulkDomainAction") String bulkDomainAction,
      @OptionalJsonPayload Optional<JsonElement> optionalJsonPayload) {
    super(consoleApiParams);
    this.gson = gson;
    this.eppController = eppController;
    this.registrarId = registrarId;
    this.bulkDomainAction = bulkDomainAction;
    this.optionalJsonPayload = optionalJsonPayload;
  }

  @Override
  protected void postHandler(User user) {
    BulkAction bulkAction = BulkAction.valueOf(bulkDomainAction);
    JsonElement jsonPayload =
        optionalJsonPayload.orElseThrow(
            () -> new IllegalArgumentException("Bulk action payload must be present"));
    BulkDomainList domainList = gson.fromJson(jsonPayload, BulkDomainList.class);
    checkPermission(user, registrarId, ConsolePermission.EXECUTE_EPP_COMMANDS);
    ImmutableMap<String, ConsoleEppOutput> result =
        switch (bulkAction) {
          case DELETE -> handleBulkDelete(jsonPayload, domainList, user);
          case SUSPEND -> handleBulkSuspend(jsonPayload, domainList, user);
        };
    // Front end should parse situations where only some commands worked
    consoleApiParams.response().setPayload(gson.toJson(result));
    consoleApiParams.response().setStatus(SC_OK);
  }

  private ImmutableMap<String, ConsoleEppOutput> handleBulkDelete(
      JsonElement jsonPayload, BulkDomainList domainList, User user) {
    String reason = gson.fromJson(jsonPayload, BulkDomainDeleteRequest.class).reason;
    return runCommandOverDomains(
        domainList, DOMAIN_DELETE_XML.replaceAll("%REASON%", reason), user);
  }

  private ImmutableMap<String, ConsoleEppOutput> handleBulkSuspend(
      JsonElement jsonPayload, BulkDomainList domainList, User user) {
    String reason = gson.fromJson(jsonPayload, BulkDomainSuspendRequest.class).reason;
    return runCommandOverDomains(
        domainList, DOMAIN_SUSPEND_XML.replaceAll("%REASON%", reason), user);
  }

  private ImmutableMap<String, ConsoleEppOutput> runCommandOverDomains(
      BulkDomainList domainList, String xml, User user) {
    return domainList.domainList.stream()
        .collect(toImmutableMap(d -> d, d -> executeEpp(xml.replaceAll("%DOMAIN_NAME%", d), user)));
  }

  private ConsoleEppOutput executeEpp(String xml, User user) {
    return ConsoleEppOutput.fromEppOutput(
        eppController.handleEppCommand(
            new StatelessRequestSessionMetadata(
                registrarId, ProtocolDefinition.getVisibleServiceExtensionUris()),
            new PasswordOnlyTransportCredentials(),
            EppRequestSource.CONSOLE,
            false,
            user.getUserRoles().isAdmin(),
            xml.getBytes(UTF_8)));
  }

  public record ConsoleEppOutput(@Expose String message, @Expose int responseCode) {
    static ConsoleEppOutput fromEppOutput(EppOutput eppOutput) {
      Result result = eppOutput.getResponse().getResult();
      return new ConsoleEppOutput(result.getMsg(), result.getCode().code);
    }
  }
}
