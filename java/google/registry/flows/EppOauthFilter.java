package google.registry.flows;

import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.oauth.OAuthServiceFactory;
import com.google.appengine.api.oauth.OAuthServiceFailureException;

import google.registry.util.FormattingLogger;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

/**
 * The {@link EppOauthFilter} class validates oAuth requests.
 *
 * If the user is not logged in a 401 is returned
 * If the user is not an admin a 401 is returned
 * If a failure with oauth happens a 500 is returned
 */
public final class EppOauthFilter implements Filter {
  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();
  private final OAuthService oAuthService;

  public EppOauthFilter() {
    this.oAuthService = OAuthServiceFactory.getOAuthService();
  }

  public EppOauthFilter(OAuthService oAuthService) {
      this.oAuthService = oAuthService;
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {}

  @Override
  public void doFilter(ServletRequest req,
                       ServletResponse res,
                       FilterChain filterChain) throws IOException, ServletException {
    final String scope = "https://www.googleapis.com/auth/userinfo.email";
    try {
      if (!oAuthService.isUserAdmin(scope)) {
        ((HttpServletResponse) res).setStatus(401);
        return;
      }
    } catch (OAuthRequestException ex) {
      logger.info("User not logged in.");
      ((HttpServletResponse) res).setStatus(401);
      return;
    } catch (OAuthServiceFailureException ex) {
      logger.severe(ex, "Unknown error while communicating with Oauth service");
      ((HttpServletResponse) res).setStatus(500);
      return;
    }

    filterChain.doFilter(req, res);
  }

  @Override
  public void destroy() {}
}
