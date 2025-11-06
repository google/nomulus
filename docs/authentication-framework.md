# Authentication framework

Nomulus performs authentication and authorization on a per-request basis. Each
endpoint action defined has an `@Action` annotation with an `auth` attribute
which determines the ways a request can authenticate itself, as well as which
requests will be authorized to invoke the action.

## Authentication and authorization properties

The `auth` attribute is an enumeration. Each value of the enumeration
corresponds to a pair of properties:

*   the *minimum authentication level* which is authorized to run the action
*   the *user policy* for the action

### Authentication Levels

There exist three levels of authentication level:

*   `NONE`: no authentication was found
*   `APP`: the request was authenticated, but no user was present
*   `USER`: the request was authenticated with a specific user

`NONE` and `USER` are fairly straightforward results (either no authentication
was present, or a user was present), but `APP` is a bit of a special case. It
exists for requests coming from service accounts, Cloud Scheduler, or the
proxy -- requests which are authenticated but don't necessarily come from any
one particular "user" per se. That being said, authorized users *can* manually
run these tasks; it's just that service accounts can too.

Each action has a minimum request authentication level. Some actions (e.g. RDAP)
are completely open to the public, and have a minimum level of `NONE`. Some
require authentication but not necessarily a user, and have a minimum level of
`APP`. And some cannot function properly without knowing the exact user, and
have a minimum level of `USER`.

### User policy

The user policy indicates what kind of user is authorized to execute the action.
There are two possible values:

*   `PUBLIC`: an authenticated user is required, but any user will do
    (authorization is done at a later state)
*   `ADMIN`: there must be an authenticated user with admin privileges (this
    includes service accounts)

Note that the user policy applies only to the automatic checking done by the
framework before invoking the action. The action itself may do more checking.
For instance, the registrar console's main page has no authentication at all,
and all requests are permitted. However, the first thing the code does is check
whether a user was found. If not, it issues a redirect to the login page.

Likewise, other pages of the registrar console have a user policy of `PUBLIC`,
meaning that any logged-in user can access the page. However, the code then
looks up the user to make sure he or she is associated with a registrar.

Also note that the user policy only applies when there is actually a user. Some
actions can be executed either by an admin user or by an internal request coming
from a task queue, which will not have a defined user at all. So rather than
determining the minimum user level, this setting should be thought of as
determining the minimum level a user must have *if there is a user at all*. To
require that there be a user, set the minimum authentication level to `USER`.

### Allowed authentication and authorization values

There are three pairs of authentication level + user policy that are used in
Nomulus (or even make sense). These are:

*   `AUTH_PUBLIC`: Allow all access and don't attempt to authenticate. This is
    used for completely public endpoints such as RDAP.
*   `AUTH_PUBLIC_LOGGED_IN`: Allow access only by users authenticated with some
    type of OAuth token. This allows all users (`UserPolicy.PUBLIC`) but
    requires that a particular user exists and is logged in (`AuthLevel.USER`).
    This is used primarily for the registrar console.
*   `AUTH_ADMIN`: Allow access only by admin users or internal requests
    (including Cloud Scheduler tasks). This is appropriate for actions that
    should only be accessed by someone trusted (as opposed to anyone with a
    Google login). This permits app-internal authentication (`AuthLevel.APP`)
    but if a user is present, it must be an admin (`UserPolicy.ADMIN`). This is
    used by many automated requests, as well as the proxy.

### Action setting golden files

To make sure that the authentication and authorization settings are correct and
expected for all actions, a unit test uses reflection to compare all defined
actions for a specific service to a
[golden file](https://github.com/google/nomulus/blob/master/core/src/test/resources/google/registry/module/routing.txt)
containing the correct settings.

Each line in the file lists a path, the class that handles that path, the
allowable HTTP methods (meaning GET and POST, as opposed to the authentication
methods described above), the value of the `automaticallyPrintOk` attribute (not
relevant for purposes of this document), and the two authentication and
authorization settings described above. Whenever actions are added, or their
attributes are modified, the golden file needs to be updated.

The golden file also serves as a convenient place to check out how things are
set up. For instance, the backend actions are accessible to admins and internal
requests only, the pubapi requests are open to the public, and console requests
require an authenticated user.

### Example

The `EppTlsAction` class handles EPP commands which arrive from the proxy via
HTTP. Only admin users and internal requests should be allowed to execute this
action, to avoid anyone on the Internet sending us random EPP commands. Further,
the HTTP method needs to be `POST`, so that the EPP command is contained in the
body rather than the URL itself (which could be logged). Therefore, the class
definition looks like:

```java

@Action(
    service = Action.Service.FRONTEND,
    path = "/_dr/epp",
    method = Method.POST,
    auth = Auth.AUTH_ADMIN)
public class EppTlsAction implements Runnable {
...
```

and the corresponding line in frontend_routing.txt (including the header line)
is:

```shell
SERVICE   PATH             CLASS              METHODS     OK MIN  USER_POLICY
FRONTEND  /_dr/epp         EppTlsAction       POST        n  APP  ADMIN
```

## Implementation

The code implementing the authentication and authorization framework is
contained in the `google.registry.request.auth` package. The main method is
`authorize()`, in `RequestAuthenticator`. This method takes the auth settings
and an HTTP request, and tries to authenticate and authorize the request,
returning the result of its attempts. Note that failed authorization (in which
case `authorize()` returns `Optional.absent()`) is different from the case where
nothing can be authenticated, but the action does not require any; in that case,
`authorize()` succeeds, returning the special result
AuthResult.NOT_AUTHENTICATED.

The ultimate caller of `authorize()` is
`google.registry.request.RequestHandler`, which is responsible for routing
incoming HTTP requests to the appropriate action. After determining the
appropriate action, and making sure that the incoming HTTP method is appropriate
for the action, it calls `authorize()`, and rejects the request if authorization
fails.

### Authentication methods

Nomulus requests are authenticated via OIDC token authentication, though these
tokens can be created and validated in two ways. In each case, the
authentication mechanism converts an HTTP request to an authentication result,
which consists of an authentication level, a possible user object, and a
possible service account email.

#### `IapOidcAuthenticationMechanism`

Most requests, e.g. the registrar console or Nomulus CLI requests) are routed
through GCP's
[Identity-Aware Proxy](https://docs.cloud.google.com/iap/docs/concepts-overview).
This forces the user to log in to some GAIA account (specifically, one that is
given access to the project). We attempt to validate a provided IAP OIDC token
with the IAP issuer URL (`https://cloud.google.com/iap`) and the proper IAP
audience (`/projects/{projectId}/global/backendServices/{serviceId}`), where
`projectId` refers to the GCP project, and `serviceId` refers to the service ID
retrievable from the
[IAP configuration page](https://pantheon.corp.google.com/security/iap).
Ideally, this service ID corresponds to the HTTPS load balancer that distributes
requests to the GKE pods.

Note: the local Nomulus CLI's
[LoginCommand](https://github.com/google/nomulus/blob/master/core/src/main/java/google/registry/tools/LoginCommand.java)
uses a special-case form of this where it saves long-lived IAP credentials
locally.

#### `RegularOidcAuthenticationMechanism`

Service account requests ( e.g.
[Cloud Scheduler jobs](https://docs.cloud.google.com/scheduler/docs/schedule-run-cron-job))
or requests coming through the proxy use a non-IAP OIDC token provided by the
caller. These requests have a different issuer URL (
`https://accounts.google.com`) and use the fairly standard OAuth bearer token
architecture -- an `Authorization` HTTP header of the form "Bearer: XXXX".

### Configuration

The `auth` block of the configuration requires two fields: *
`allowedServiceAccountEmails` is the list of service accounts that should be
allowed to run tasks when internally authenticated. This will likely include
whatever service account runs Nomulus in Google Kubernetes Engine, as well as
the Cloud Scheduler service account. * `oauthClientId` is the OAuth client ID
associated with IAP. This is retrievable from the
[Clients page](https://pantheon.corp.google.com/auth/clients) of GCP after
enabling the Identity-Aware Proxy. It should look something like
`someNumbers-someNumbersAndLetters.apps.googleusercontent.com`
