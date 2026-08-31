package com.intelligentresume.auth.controller;

import com.intelligentresume.auth.dto.CurrentUserResponse;
import com.intelligentresume.auth.dto.ChangeEmailRequest;
import com.intelligentresume.auth.dto.ChangePasswordRequest;
import com.intelligentresume.auth.dto.LoginRequest;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.dto.UpdateProfileRequest;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String REFRESH_HEADER = "X-Refresh-Token";

    private final AuthService authService;
    private final String refreshCookieName;
    private final long refreshCookieMaxAge;
    private final boolean refreshCookieSecure;

    public AuthController(AuthService authService,
                          @Value("${app.jwt.refresh-cookie.name}") String refreshCookieName,
                          @Value("${app.jwt.refresh-cookie.max-age}") long refreshCookieMaxAge,
                          @Value("${app.jwt.refresh-cookie.secure}") boolean refreshCookieSecure) {
        this.authService = authService;
        this.refreshCookieName = refreshCookieName;
        this.refreshCookieMaxAge = refreshCookieMaxAge;
        this.refreshCookieSecure = refreshCookieSecure;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<TokenResponse>> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        return withRefreshCookie(authService.register(request), httpRequest, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        TokenResponse tokens = authService.login(request);
        return withRefreshCookie(tokens, httpRequest);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(HttpServletRequest httpRequest) {
        String refresh = extractRefreshToken(httpRequest);
        String ua = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        String ip = clientIp(httpRequest);
        return withRefreshCookie(authService.refresh(refresh, ua, ip), httpRequest);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest) {
        authService.logout(extractRefreshToken(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        authService.logoutAll(userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.currentUser(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PatchMapping("/me")
    public ApiResponse<CurrentUserResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.updateProfile(currentUserId(httpRequest), request), traceId(httpRequest));
    }

    @PostMapping("/me/email")
    public ResponseEntity<ApiResponse<Void>> changeEmail(@Valid @RequestBody ChangeEmailRequest request, HttpServletRequest httpRequest) {
        authService.changeEmail(currentUserId(httpRequest), request);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    @PostMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        authService.changePassword(currentUserId(httpRequest), request);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteAccount(HttpServletRequest httpRequest) {
        authService.deleteAccount(currentUserId(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    private String extractRefreshToken(HttpServletRequest request) {
        // 优先 header(便于前端调试),其次 cookie
        String header = request.getHeader(REFRESH_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (refreshCookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("currentUserId");
        if (attr == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return (Long) attr;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }

    private ResponseEntity<ApiResponse<TokenResponse>> withRefreshCookie(TokenResponse tokens, HttpServletRequest request) {
        return withRefreshCookie(tokens, request, HttpStatus.OK);
    }

    private ResponseEntity<ApiResponse<TokenResponse>> withRefreshCookie(TokenResponse tokens, HttpServletRequest request, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()).toString())
                .body(ApiResponse.success(tokens, traceId(request)));
    }

    private ResponseCookie refreshCookie(String refreshToken) {
        return ResponseCookie.from(refreshCookieName, refreshToken)
                .path("/")
                .maxAge(refreshCookieMaxAge)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .build();
    }

    private ResponseCookie expiredRefreshCookie() {
        return ResponseCookie.from(refreshCookieName, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .build();
    }
}
