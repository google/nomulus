package google.registry.ui.server.console.settings;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.QueryComposer.Comparator;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.ui.server.console.ConsoleActionBase;
import javax.inject.Inject;

@Action(
    service = Action.Service.DEFAULT,
    path = ContactGetAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ContactGetAction extends ConsoleActionBase {
  public static final String PATH = "/console-api/settings/contacts/get";
  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;
  private final String registrarId;

  @Inject
  public ContactGetAction(
      AuthResult authResult,
      Response response,
      Gson gson,
      @Parameter("registrarId") String registrarId) {
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
    this.registrarId = registrarId;
  }

  @Override
  public void run() {
    if (!verifyUserPermissions(true, registrarId, authResult, response)) {
      return;
    }

    ImmutableList<RegistrarPoc> am =
        tm().transact(
                () ->
                    tm().createQueryComposer(RegistrarPoc.class)
                        .where("registrarId", Comparator.EQ, registrarId)
                        .list());

    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
    response.setPayload(gson.toJson(am));
  }
}
