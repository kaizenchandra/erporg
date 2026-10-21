package com.atlas.configuration;

import com.atlas.modules.corehr.api.WorkforceAccess;
import com.atlas.shared.domain.AccessDenied;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class EmploymentAccessFilter extends OncePerRequestFilter {
  public static final String EPOCH_ATTRIBUTE = "atlas.workforceEpoch";
  private final WorkforceAccess workforce;

  public EmploymentAccessFilter(WorkforceAccess workforce) {
    this.workforce = workforce;
  }

  public static void reject(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    var cookie = new Cookie("JSESSIONID", "");
    cookie.setPath("/");
    cookie.setMaxAge(0);
    cookie.setHttpOnly(true);
    cookie.setSecure(request.isSecure());
    cookie.setAttribute("SameSite", "Strict");
    response.addCookie(cookie);
    response.setStatus(401);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      try {
        workforce.verifyIssuer(
            auth.getName(),
            auth.getPrincipal()
                    instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser oidc
                ? oidc.getIssuer().toString()
                : "");
        var epoch = workforce.sessionEpoch(auth.getName());
        var session = request.getSession(false);
        if (session == null || !epoch.equals(session.getAttribute(EPOCH_ATTRIBUTE))) {
          reject(request, response);
          return;
        }
      } catch (AccessDenied denied) {
        reject(request, response);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
