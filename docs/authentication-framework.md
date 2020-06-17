# Authentication framework

Nomulus performs authentication and authorization on a per-request basis. Each
endpoint action defined has an `@Action` annotation with an `auth` attribute
which determines the ways a request can authenticate itself, as well as which
requests will be authorized to invoke the action.

## Authentication and authorization properties

The `auth` attribute is an enumeration. Each value of the enumeration
corresponds to a triplet of properties:

* the *authentication methods* allowed by the action
* the *minimum authentication level* which is authorized to run the action
* the *user policy* for the action

### Authentication methods

Authentication methods are ways whereby the request can authenticate itself to
the system. In the code, an *authentication mechanism* is a class which handles
a particular authentication method. There are currently three methods:

* `INTERNAL`: used by requests generated from App Engine task queues; these
    requests do not have a user, because they are system-generated, so
    authentication consists solely of verifying that the request did indeed
    come from a task queue

* `API`: authentication using an API; the Nomulus release ships with one API
    authentication mechanism, OAuth 2, but you can write additional custom
    mechanisms to handle other protocols if needed

* `LEGACY`: authentication using the standard App Engine `UserService` API,
    which authenticates based on cookies and XSRF tokens

The details of the associated authentication mechanism classes are given later.

### Authentication levels

Each authentication method listed above can authenticate at one of three levels:

* `NONE`: no authentication was found
* `APP`: the request was authenticated, but no user was present
* `USER`: the request was authenticated with a specific user

For instance, `INTERNAL` authentication never returns an authentication level of
`USER`, because internal requests generated from App Engine task queues do not
execute as a particular end user account. `LEGACY` authentication, on the other
hand, never returns an authentication level of `APP`, because authentication is
predicated on identifying the user, so the only possible answers are `NONE` and
`USER`.

Each action has a minimum request authentication level. Some actions are
completely open to the public, and have a minimum level of `NONE`. Some require
authentication but not a user, and have a minimum level of `APP`. And some
cannot function properly without knowing the exact user, and have a minimum
level of `USER`.

### User policy

The user policy indicates what kind of user is authorized to execute the action.
There are three possible values:

* `IGNORED`: the user information is ignored
* `PUBLIC`: an authenticated user is required, but any user will do
* `ADMIN`: there must be an authenticated user with admin privileges

Note that the user policy applies only to the automatic checking done by the
framework before invoking the action. The action itself may do more checking.
For instance, the registrar console's main page has no authentication at all,
and all requests are permitted. However, the first thing the code does is check
whether a user was found. If not, it issues a redirect to the login page.

Likewise, other pages of the registrar console have a user policy of `PUBLIC`,
meaning that any logged-in user can access the page. However, the code then
looks up the user to make sure he or she is associated with a registrar.
Admins can be granted permission to the registrar console by configuring a
special registrar for internal admin use, using the `registryAdminClientId`
setting. See the [global configuration
guide](./configuration.md#global-configuration) for more details.

Also note that the user policy only applies when there is actually a user. Some
actions can be executed either by an admin user or by an internal request coming
from a task queue, which will not have a defined user at all. So rather than
determining the minimum user level, this setting should be thought of as
determining the minimum level a user must have *if there is a user at all*. To
require that there be a user, set the minimum authentication level to `USER`.

### Allowed authentication and authorization values

Not all triplets of the authentication method, minimum level and user policy
make sense. A master enumeration lists all the valid triplets. They are:

* `AUTH_PUBLIC_ANONYMOUS`: Allow all access, and don't attempt to authenticate.
    The only authentication method is `INTERNAL`, with a minimum level of
    `NONE`. Internal requests will be flagged as such, but everything else
    passes the authorization check with a value of `NOT_AUTHENTICATED`.

* `AUTH_PUBLIC`: Allow all access, but attempt to authenticate the user. All
    three authentication methods are specified, with a minimum level of `NONE`
    and a user policy of `PUBLIC`. If the user can be authenticated by any
    means, the identity is passed to the request. But if not, the request still
    passes the authorization check, with a value of `NOT_AUTHENTICATED`.

* `AUTH_PUBLIC_LOGGED_IN`: Allow access only by authenticated users. The
    `API` and `LEGACY` authentication methods are supported, but not `INTERNAL`,
    because that does not identify a user. The minimum level is `USER`, with a
    user policy of `PUBLIC`. Only requests with a user authenticated via either
    the legacy, cookie-based method or an API method (e.g. OAuth 2) are
    authorized to run the action.

* `AUTH_INTERNAL_OR_ADMIN`: Allow access only by admin users or internal
    requests. This is appropriate for actions that should only be accessed by
    someone trusted (as opposed to anyone with a Google login). This currently
    allows only the `INTERNAL` and `API` methods, meaning that an admin user
    cannot authenticate themselves via the legacy authentication mechanism,
    which is used only for the registrar console. The minimum level is `APP`,
    because we don't require a user for internal requests, but the user policy
    is `ADMIN`, meaning that if there *is* a user, it needs to be an admin.

*  `AUTH_PUBLIC_OR_INTERNAL`: Allows anyone access, as long as they use OAuth to
    authenticate. Also allows access from App Engine task-queue. Note that OAuth
    client ID still needs to be allow-listed in the config file for OAuth-based
    authentication to succeed. This is mainly used by the proxy.

### Action setting golden files

To make sure that the authentication and authorization settings are correct for
all actions, a unit test uses reflection to compare all defined actions for a
specific service to a golden file containing the correct settings. These files
are:

* `frontend_routing.txt` for the default (frontend) service
* `backend_routing.txt` for the backend service
* `tools_routing.txt` for the tools service

Each of these files consists of lines listing a path, the class that handles
that path, the allowable HTTP methods (meaning GET and POST, as opposed to the
authentication methods described above), the value of the `automaticallyPrintOk`
attribute (not relevant for purposes of this document), and the three
authentication and authorization settings described above. Whenever actions are
added, or their attributes are modified, the golden files need to be updated.

The golden files also serve as a convenient place to check out how things are
set up. For instance, the tools actions are, for the most part, accessible to
admins and internal requests only. The backend actions are mostly accessible
only to internal requests. And the frontend actions are a grab-bag; some are
open to the public, some to any user, some only to admins, etc.

### Example

The `EppTlsAction` class handles EPP commands which arrive from the proxy via
HTTP. Only admin users and internal requests should be allowed to execute this
action, to avoid anyone on the Internet sending us random EPP commands. Further,
the HTTP method needs to be `POST`, so that the EPP command is contained in the
body rather than the URL itself (which could be logged). Therefore, the class
definition looks like:

```java
@Action(
  path = "/_dr/epp",
  method = Method.POST,
  auth = Auth.AUTH_INTERNAL_OR_ADMIN
)
public class EppTlsAction implements Runnable {
...
```

and the corresponding line in frontend_routing.txt (including the header line)
is:

```shell
PATH         CLASS           METHODS  OK AUTH_METHODS        MIN  USER_POLICY
/_dr/epp     EppTlsAction    POST     n  INTERNAL,API        APP  ADMIN
```

## Implementation

The code implementing the authentication and authorization framework is
contained in the `google.registry.request.auth` package. The main method is
`authorize()`, in `RequestAuthenticator`. This method takes the auth settings
and an HTTP request, and tries to authenticate and authorize the request using
any of the specified methods, returning the result of its attempts. Note that
failed authorization (in which case `authorize()` returns `Optional.absent()`)
is different from the case where nothing can be authenticated, but the action
does not require any; in that case, `authorize()` succeeds, returning the
special result AuthResult.NOT_AUTHENTICATED.

There are separate classes (described below) for the mechanism which handles
each authentication method. The list of allowable API authentication mechanisms
(by default, just OAuth 2) is configured in `AuthModule`.

The ultimate caller of `authorize()` is
`google.registry.request.RequestHandler`, which is responsible for routing
incoming HTTP requests to the appropriate action. After determining the
appropriate action, and making sure that the incoming HTTP method is appropriate
for the action, it calls `authorize()`, and rejects the request if authorization
fails.

### `LegacyAuthenticationMechanism`

Legacy authentication is straightforward, because the App Engine `UserService`
API does all the work. Because the protocol might be vulnerable to an XSRF
attack, the authentication mechanism issues and checks XSRF tokens as part
of the process if the HTTP method is not GET or HEAD.

### `OAuthAuthenticationMechanism`

OAuth 2 authentication is performed using the App Engine `OAuthService` API.
There are three Nomulus configuration values involved:

* `availableOauthScopes` is the set of OAuth scopes passed to the service to
    be checked for their presence.

* `requiredOauthScopes` is the set of OAuth scopes which must be present. This
    should be a subset of the available scopes. All scopes in this set must be
    present for authentication to succeed.

* `allowedOauthClientIds` is the set of allowable OAuth client IDs. Any client
    ID in this set is sufficient for successful authentication.

The code looks for an `Authorization` HTTP header of the form "BEARER XXXX...",
containing the access token. If it finds one, it calls `OAuthService` to
validate the token, check that the scopes and client ID match, and retrieve the
flag indicating whether the user is an admin.

### `AppEngineInternalAuthenticationMechanism`

Detection of internal requests is a little hacky. App Engine uses a special HTTP
header, `X-AppEngine-QueueName`, to indicate the queue from which the request
originates. If this header is present, internal authentication succeeds. App
Engine normally strips this header from external requests, so only internal
requests will be authenticated.

App Engine has a special carve-out for admin users, who are allowed to specify
headers which do not get stripped. So an admin user can use a command-line
utility like `curl` to craft a request which appears to Nomulus to be an
internal request. This has proven to be useful, facilitating the testing of
actions which otherwise could only be run via a dummy cron job.

However, it only works if App Engine can authenticate the user as an admin via
the `UserService` API. OAuth won't work, because authentication is performed by
the Nomulus code, and the headers will already have been stripped by App Engine
before the request is executed. Only the legacy, cookie-based method will work.

Be aware that App Engine defines an "admin user" as anyone with access to the
App Engine project, even those with read-only access.

## Other topics

### OAuth 2 not supported for the registry console

Currently, OAuth 2 is only supported for requests which specify the
`Authorization` HTTP header. The OAuth code reads this header and passes it to
the Google OAuth server (no other authentication servers are currently
supported) to verify the user's identity. This works fine for the `nomulus`
command-line tool.

It doesn't work for browser-based interactions such as the registrar console.
For that, we will (we think) need to redirect the user to the authentication
server, and upon receiving the user back, fish out the code and convert it to a
token which we store in a cookie. None of this is particularly hard, but for the
moment it seems easier to stick with the legacy App Engine UserService API. Of
course, contributions from the open-source community are welcome. :)

### Authorization via `web.xml`

Before the modern authentication and authorization framework described in this
document was put in place, Nomulus used to be protected by directives in the
`web.xml` file which allowed only logged-in users to access most endpoints. This
had the advantage of being very easy to implement, but it came with some
drawbacks, the primary one being lack of support for OAuth 2. App Engine's
standard login detection works fine when using a browser, but does not handle
cases where the request is coming from a standalone program such as the
`nomulus` command-line tool. By moving away from the `web.xml` approach, we
gained more flexibility to support an array of authentication and authorization
schemes, including custom ones developed by the Nomulus community, at the
expense of having to perform the authentication and authorization ourselves in
the code.
