package google.registry.ui.server.console.settings;


import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.ui.server.console.ConsoleActionBase;
import google.registry.ui.server.registrar.RegistrarSettingsAction;
import java.util.Collections;
import java.util.stream.Collectors;
import javax.inject.Inject;

@Action(
    service = Action.Service.DEFAULT,
    path = ContactPostAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ContactPostAction extends ConsoleActionBase {
  private static boolean hasPhone(RegistrarPoc contact) {
    return contact.getPhoneNumber() != null;
  }

  public static final String PATH = "/console-api/settings/contacts/post";
  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;
  private final ImmutableSet<RegistrarPoc> contacts;
  private final String registrarId;

  @Inject
  public ContactPostAction(
      AuthResult authResult,
      Response response,
      Gson gson,
      @Parameter("registrarId") String registrarId,
      @Parameter("contacts") ImmutableSet<RegistrarPoc> contacts) {
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
    this.contacts = contacts;
    this.registrarId = registrarId;
  }

  @Override
  public void run() {
    if (!verifyUserPermissions(false, registrarId, authResult, response)) {
      return;
    }

    Registrar registrar =
        Registrar.loadByRegistrarId(registrarId)
            .orElseThrow(
                () ->
                    new IllegalStateException(String.format("Unknown registrar %s", registrarId)));

    ImmutableSet<RegistrarPoc> oldContacts = registrar.getContacts();
    // TODO: @ptkach - refactor out contacts update functionality after RegistrarSettingsAction is
    // deprecated
    ImmutableSet<RegistrarPoc> updatedContacts =
        RegistrarSettingsAction.readContacts(
            registrar,
            oldContacts,
            Collections.singletonMap(
                "contacts",
                contacts.stream().map(c -> c.toJsonMap()).collect(Collectors.toList())));
    RegistrarSettingsAction.checkContactRequirements(oldContacts, updatedContacts);
    RegistrarPoc.updateContacts(registrar, updatedContacts);
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }
}
