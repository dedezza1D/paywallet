package br.com.paywallet.auth;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
class TransactionPinInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    static final String HEADER = "X-Transaction-Pin";

    private final TransactionPinService pins;

    TransactionPinInterceptor(TransactionPinService pins) {
        this.pins = pins;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(RequiresTransactionPin.class)) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            pins.verify(Long.valueOf(auth.getName()), request.getHeader(HEADER));
        }
        return true;
    }
}
