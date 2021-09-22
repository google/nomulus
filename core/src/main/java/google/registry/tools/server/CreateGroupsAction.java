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

package google.registry.tools.server;

import static google.registry.export.SyncGroupMembersAction.getGroupEmailAddressForContactType;
import static google.registry.request.Action.Method.POST;
import static java.util.Arrays.asList;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GroupsConnection;
import google.registry.groups.GroupsConnection.Role;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Concurrent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;

/** Action that creates Google Groups for a registrar's mailing lists. */
@Action(
    service = Action.Service.TOOLS,
    path = CreateGroupsAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class CreateGroupsAction implements Runnable {

  public static final String PATH = "/_dr/admin/createGroups";
  public static final String CLIENT_ID_PARAM = "clientId";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int NUM_SIMULTANEOUS_CONNECTIONS = 5;

  @Inject GroupsConnection groupsConnection;
  @Inject Response response;
  @Inject @Config("gSuiteDomainName") String gSuiteDomainName;
  @Inject @Parameter("clientId") Optional<String> clientId;
  @Inject CreateGroupsAction() {}

  @Override
  public void run() {
    final Registrar registrar = initAndLoadRegistrar();
    if (registrar == null) {
      return;
    }
    List<RegistrarContact.Type> types = asList(RegistrarContact.Type.values());
    // Concurrently create the groups for each RegistrarContact.Type, collecting the results from
    // each call (which are either an Exception if it failed, or absent() if it succeeded).
    List<Optional<Exception>> results =
        Concurrent.transform(
            types,
            NUM_SIMULTANEOUS_CONNECTIONS,
            type -> {
              try {
                String groupKey =
                    getGroupEmailAddressForContactType(
                        registrar.getRegistrarId(), type, gSuiteDomainName);
                String parentGroup =
                    getGroupEmailAddressForContactType("registrar", type, gSuiteDomainName);
                // Creates the group, then adds it as a member to the global registrar group for
                // that type.
                groupsConnection.createGroup(groupKey);
                groupsConnection.addMemberToGroup(parentGroup, groupKey, Role.MEMBER);
                return Optional.empty();
              } catch (Exception e) {
                return Optional.of(e);
              }
            });
    // Return the correct server response based on the results of the group creations.
    if (results.stream().anyMatch(Optional::isPresent)) {
      StringWriter responseString = new StringWriter();
      PrintWriter responseWriter = new PrintWriter(responseString);
      for (int i = 0; i < results.size(); i++) {
        Optional<Exception> e = results.get(i);
        if (e.isPresent()) {
          responseWriter.append(types.get(i).getDisplayName()).append(" => ");
          e.get().printStackTrace(responseWriter);
          logger.atSevere().withCause(e.get()).log(
              "Could not create Google Group for registrar %s for type %s.",
              registrar.getRegistrarName(), types.get(i));
        } else {
          responseWriter.printf("%s => Success%n", types.get(i).getDisplayName());
        }
      }
      throw new InternalServerErrorException(responseString.toString());
    } else {
      response.setStatus(SC_OK);
      response.setPayload("Success!");
      logger.atInfo().log(
          "Successfully created groups for registrar %s.", registrar.getRegistrarName());
    }
  }

  private Registrar initAndLoadRegistrar() {
    if (!clientId.isPresent()) {
      respondToBadRequest("Error creating Google Groups, missing parameter: clientId");
    }
    Optional<Registrar> registrar = Registrar.loadByRegistrarId(clientId.get());
    if (!registrar.isPresent()) {
      respondToBadRequest(String.format(
          "Error creating Google Groups; could not find registrar with id %s", clientId.get()));
    }
    return registrar.get();
  }

  private void respondToBadRequest(String message) {
    logger.atSevere().log(message);
    throw new BadRequestException(message);
  }
}
