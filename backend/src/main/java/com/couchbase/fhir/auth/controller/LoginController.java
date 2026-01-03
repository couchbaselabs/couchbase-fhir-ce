package com.couchbase.fhir.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Login controller for OAuth 2.0 authorization flow
 * Provides HTML login pages for authenticating users
 * 
 * Two endpoints:
 * - /login - Legacy/general purpose login (kept for compatibility)
 * - /oauth2/login - OAuth2 SMART app authentication (preferred)
 * 
 * Note: Admin UI uses /api/auth/login (API endpoint, not form)
 * Note: Consent page is handled by ConsentController
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(HttpServletRequest request, HttpServletResponse response, Model model) {
        // Prevent caching of login page to ensure users always see the latest version
        // This prevents browser from serving stale cached login pages
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        
        // Pass the form action URL to the template
        model.addAttribute("loginUrl", "/login");
        
        return "login";
    }
    
    @GetMapping("/oauth2/login")
    public String oauth2Login(HttpServletRequest request, HttpServletResponse response, Model model) {
        // OAuth2-specific login page (same template for now)
        // Prevents caching to ensure users always see the latest version
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        
        // Pass the form action URL to the template
        model.addAttribute("loginUrl", "/oauth2/login");
        
        return "login";
    }
}

