package org.portage.authorizationserver.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Serializes code exchange, refresh and revocation in the database, including across JVMs.
 * Buffer the OAuth response until commit; never return an uncommitted rotated token.
 * A second concurrent use is treated strictly as replay, revoking the first use's family.
 */
public final class TokenTransactionFilter extends OncePerRequestFilter {
    private final TransactionTemplate transactions;
    private final RotatingAuthorizationService authorizations;
    public TokenTransactionFilter(TransactionTemplate transactions, RotatingAuthorizationService authorizations) {
        this.transactions = transactions;
        this.authorizations = authorizations;
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !("/oauth2/token".equals(request.getServletPath())
                || "/oauth2/revoke".equals(request.getServletPath()));
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var buffered = new ContentCachingResponseWrapper(response);
        try {
            transactions.executeWithoutResult(status -> {
                authorizations.lock();
                try { chain.doFilter(request, buffered); }
                catch (IOException | ServletException e) { throw new FilterFailure(e); }
            });
        } catch (FilterFailure e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw (ServletException) e.getCause();
        }
        buffered.copyBodyToResponse();
    }
    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Exception cause) { super(cause); }
    }
}
