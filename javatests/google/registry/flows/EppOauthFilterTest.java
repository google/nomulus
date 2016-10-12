package google.registry.flows;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.oauth.OAuthServiceFailureException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

@RunWith(MockitoJUnitRunner.class)
public class EppOauthFilterTest {
  @Mock
  private OAuthService oAuthService;
  @InjectMocks
  private EppOauthFilter eppOauthFilter;

  @Test
  public void testDoFilterShouldCallDoFilterWhenUserIsAdmin() throws Exception {
    when(oAuthService.isUserAdmin(any(String.class))).thenReturn(true);
    ServletRequest req = mock(ServletRequest.class);
    ServletResponse res = mock(ServletResponse.class);
    FilterChain filterChain = mock(FilterChain.class);

    eppOauthFilter.doFilter(req, res, filterChain);

    verify(filterChain).doFilter(req, res);
  }

  @Test
  public void testDoFilterShouldSetStatusTo401WhenUserIsNotAdmin() throws Exception {
    when(oAuthService.isUserAdmin(any(String.class))).thenReturn(false);
    ServletRequest req = mock(ServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain filterChain = mock(FilterChain.class);

    eppOauthFilter.doFilter(req, res, filterChain);

    verify(res).setStatus(401);
  }

  @Test
  public void testDoFilterShouldSetStatusTo401WhenOAuthRequestException() throws Exception {
    when(oAuthService.isUserAdmin(any(String.class))).thenThrow(new OAuthRequestException("Test"));
    ServletRequest req = mock(ServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain filterChain = mock(FilterChain.class);

    eppOauthFilter.doFilter(req, res, filterChain);

    verify(res).setStatus(401);
  }

  @Test
  public void testDoFilterShouldSetStatusTo500WhenOAuthServiceFailureException() throws Exception {
    when(oAuthService.isUserAdmin(any(String.class)))
        .thenThrow(new OAuthServiceFailureException("Test"));
    ServletRequest req = mock(ServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain filterChain = mock(FilterChain.class);

    eppOauthFilter.doFilter(req, res, filterChain);

    verify(res).setStatus(500);
  }
}
