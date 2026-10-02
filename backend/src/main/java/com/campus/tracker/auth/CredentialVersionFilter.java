package com.campus.tracker.auth;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
/** Database versions revoke sessions on their next API request, across server instances. */
public class CredentialVersionFilter extends OncePerRequestFilter {
    private final UserRepository users;
    public CredentialVersionFilter(UserRepository users){this.users=users;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(req.getRequestURI().startsWith(req.getContextPath()+"/api/")&&auth!=null&&auth.getPrincipal() instanceof CampusPrincipal principal){
            boolean valid;
            try{valid=users.credentialVersion(principal.id()).filter(v->v==principal.credentialVersion()).isPresent();}
            catch(org.springframework.dao.DataAccessException e){error(res,503,"Authentication is temporarily unavailable.");return;}
            if(!valid){PasswordController.endSession(req,res);error(res,401,"Your session ended. Sign in to continue.");return;}
        }
        chain.doFilter(req,res);
    }
    private void error(HttpServletResponse res,int status,String message) throws IOException {
        res.setStatus(status);res.setHeader("Cache-Control","no-store");res.setContentType("application/json");
        res.getWriter().write("{\"message\":\""+message+"\"}");
    }
}
