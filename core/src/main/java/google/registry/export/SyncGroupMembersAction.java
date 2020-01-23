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

package google.registry.export;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.RegistrarUtils.normalizeClientId;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GroupsConnection;
import google.registry.groups.GroupsConnection.Role;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Retrier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * Action that syncs changes to {@link RegistrarContact} entities with Google Groups.
 *
 * <p>This uses the <a href="https://developers.google.com/admin-sdk/directory/">Directory API</a>.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/syncGroupMembers",
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class SyncGroupMembersAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private enum Result {
    OK(SC_OK, "Group memberships successfully updated."),
    NOT_MODIFIED(SC_OK, "No registrar contacts have been updated since the last time servlet ran."),
    FAILED(SC_INTERNAL_SERVER_ERROR, "Error occurred while updating registrar contacts.") {
      @Override
      protected void log(Throwable cause) {
        logger.atSevere().withCause(cause).log(message);
      }};

    final int statusCode;
    final String message;

    Result(int statusCode, String message) {
      this.statusCode = statusCode;
      this.message = message;
    }

    /** Log an error message. Results that use log levels other than info should override this. */
    void log(@Nullable Throwable cause) {
      logger.atInfo().withCause(cause).log(message);
    }
  }

  @Inject GroupsConnection groupsConnection;
  @Inject @Config("gSuiteDomainName") String gSuiteDomainName;
  @Inject Response response;
  @Inject Retrier retrier;
  @Inject SyncGroupMembersAction() {}

  private void sendResponse(Result result, @Nullable List<Throwable> causes) {
    nullToEmpty(causes).forEach(result::log);
    response.setStatus(result.statusCode);
    response.setPayload(String.format("%s %s\n", result.name(), result.message));
  }

  /**
   * Returns the Google Groups email address for the given registrar clientId and
   * RegistrarContact.Type
   */
  public static String getGroupEmailAddressForContactType(
      String clientId, RegistrarContact.Type type, String gSuiteDomainName) {
    // Take the registrar's clientId, make it lowercase, and remove all characters that aren't
    // alphanumeric, hyphens, or underscores.
    return String.format(
        "%s-%s-contacts@%s",
        normalizeClientId(clientId), type.getDisplayName(), gSuiteDomainName);
  }

  /**
   * Loads all Registrars, and for each one that is marked dirty, grabs the existing group
   * memberships and updates them to reflect the current state of the RegistrarContacts.
   */
  @Override
  public void run() {
    List<Registrar> dirtyRegistrars =
        Streams.stream(Registrar.loadAllCached())
            .filter(
                registrar ->
                    registrar.isLive()
                        && registrar.getContactsRequireSyncing()
                        && registrar.getType() == Registrar.Type.REAL)
            .collect(toImmutableList());
    if (dirtyRegistrars.isEmpty()) {
      sendResponse(Result.NOT_MODIFIED, null);
      return;
    }

    ImmutableMap.Builder<Registrar, Optional<Throwable>> resultsBuilder =
        new ImmutableMap.Builder<>();
    for (final Registrar registrar : dirtyRegistrars) {
      try {
        retrier.callWithRetry(() -> syncRegistrarContacts(registrar), RuntimeException.class);
        resultsBuilder.put(registrar, Optional.empty());
      } catch (Throwable e) {
        logger.atSevere().withCause(e).log(e.getMessage());
        resultsBuilder.put(registrar, Optional.of(e));
      }
    }

    List<Throwable> errors = getErrorsAndUpdateFlagsForSuccesses(resultsBuilder.build());
    // If there were no errors, return success; otherwise return a failed status and log the errors.
    if (errors.isEmpty()) {
      sendResponse(Result.OK, null);
    } else {
      sendResponse(Result.FAILED, errors);
    }
  }

  /**
   * Parses the results from Google Groups for each registrar, setting the dirty flag to false in
   * Datastore for the calls that succeeded and accumulating the errors for the calls that failed.
   */
  private static List<Throwable> getErrorsAndUpdateFlagsForSuccesses(
      ImmutableMap<Registrar, Optional<Throwable>> results) {
    final ImmutableList.Builder<Registrar> registrarsToSave = new ImmutableList.Builder<>();
    List<Throwable> errors = new ArrayList<>();
    for (Map.Entry<Registrar, Optional<Throwable>> result : results.entrySet()) {
      if (result.getValue().isPresent()) {
        errors.add(result.getValue().get());
      } else {
        registrarsToSave.add(result.getKey().asBuilder().setContactsRequireSyncing(false).build());
      }
    }
    tm().transactNew(() -> ofy().save().entities(registrarsToSave.build()));
    return errors;
  }

  /** Syncs the contacts for an individual registrar to Google Groups. */
  private void syncRegistrarContacts(Registrar registrar) {
    String groupKey = "";
    try {
      Set<RegistrarContact> registrarContacts = registrar.getContacts();
      long totalAdded = 0;
      long totalRemoved = 0;
      for (final RegistrarContact.Type type : RegistrarContact.Type.values()) {
        groupKey = getGroupEmailAddressForContactType(
            registrar.getClientId(), type, gSuiteDomainName);
        Set<String> currentMembers = groupsConnection.getMembersOfGroup(groupKey);
        Set<String> desiredMembers =
            registrarContacts
                .stream()
                .filter(contact -> contact.getTypes().contains(type))
                .map(RegistrarContact::getEmailAddress)
                .collect(toImmutableSet());
        for (String email : Sets.difference(desiredMembers, currentMembers)) {
          groupsConnection.addMemberToGroup(groupKey, email, Role.MEMBER);
          totalAdded++;
        }
        for (String email : Sets.difference(currentMembers, desiredMembers)) {
          groupsConnection.removeMemberFromGroup(groupKey, email);
          totalRemoved++;
        }
      }
      logger.atInfo().log(
          "Successfully synced contacts for registrar %s: added %d and removed %d",
          registrar.getClientId(), totalAdded, totalRemoved);
    } catch (IOException e) {
      // Package up exception and re-throw with attached additional relevant info.
      String msg = String.format("Couldn't sync contacts for registrar %s to group %s",
          registrar.getClientId(), groupKey);
      throw new RuntimeException(msg, e);
    }
  }
}
