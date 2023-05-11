package google.registry.ui.server.console.settings;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.ui.server.registrar.JsonGetAction;
import javax.inject.Inject;

public class ContactInfoAction {

  private static boolean isAuthorized(AuthResult authResult) {
    if (!authResult.isAuthenticated() || !authResult.userAuthInfo().isPresent()) {
      return false;
    }
    UserAuthInfo authInfo = authResult.userAuthInfo().get();
    if (!authInfo.consoleUser().isPresent()) {
      return false;
    }
    return true;
  }

  @Action(
      service = Action.Service.DEFAULT,
      path = ContactInfoGetAction.PATH,
      method = Action.Method.GET,
      auth = Auth.AUTH_PUBLIC_LOGGED_IN)
  public static class ContactInfoGetAction implements JsonGetAction {

    private static final String PATH = "/console-api/settings/contacts/get";
    private final AuthResult authResult;
    private final Response response;
    private final Gson gson;
    private final String registrarId;

    @Inject
    public ContactInfoGetAction(
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
      if (!isAuthorized(authResult)) {
        response.setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
        return;
      }

      Iterable<RegistrarPoc> am =
          tm().transact(
                  () ->
                      tm().query(
                              "FROM RegistrarPoc WHERE registrarId = :registrarId",
                              RegistrarPoc.class)
                          .setParameter("registrarId", registrarId)
                          .getResultStream()
                          .collect(toImmutableList()));

      GsonBuilder builder = new GsonBuilder();
      Gson gson = builder.create();
      response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
      response.setPayload(gson.toJson(am));
    }
  }

  @Action(
      service = Action.Service.DEFAULT,
      path = ContactInfoPostAction.PATH,
      method = Action.Method.POST,
      auth = Auth.AUTH_PUBLIC_LOGGED_IN)
  public static class ContactInfoPostAction implements JsonGetAction {

    private static final String PATH = "/console-api/settings/contacts/upd";
    private final AuthResult authResult;
    private final Response response;
    private final Gson gson;
    private final String contacts;
    private final String registrarId;

    @Inject
    public ContactInfoPostAction(
        AuthResult authResult,
        Response response,
        Gson gson,
        @Parameter("registrarId") String registrarId,
        @Parameter("contacts") String contacts) {
      this.authResult = authResult;
      this.response = response;
      this.gson = gson;
      this.contacts = contacts;
      this.registrarId = registrarId;
    }

    @Override
    public void run() {
      if (!isAuthorized(authResult)) {
        response.setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
        return;
      }

      RegistrarPoc[] registrarPocs = gson.fromJson(contacts, RegistrarPoc[].class);
      Registrar registrar =
          Registrar.loadByRegistrarId(registrarId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          String.format("Unknown registrar %s", registrarId)));

      RegistrarPoc.updateContacts(registrar, ImmutableSet.copyOf(registrarPocs));
    }
  }
}
