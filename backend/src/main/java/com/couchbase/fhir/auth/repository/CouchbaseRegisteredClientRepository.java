package com.couchbase.fhir.auth.repository;

import com.couchbase.admin.oauth.model.OAuthClient;
import com.couchbase.admin.oauth.service.OAuthClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;
import java.util.Optional;

/**
 * Custom RegisteredClientRepository that loads OAuth clients from Couchbase.
 * 
 * This repository wraps the OAuthClientService and converts Couchbase OAuthClient
 * entities into Spring's RegisteredClient format for use by Spring Authorization Server.
 * 
 * Works in conjunction with a composite repository that also includes in-memory clients
 * (like the admin-ui client).
 */
public class CouchbaseRegisteredClientRepository implements RegisteredClientRepository {
    
    private static final Logger logger = LoggerFactory.getLogger(CouchbaseRegisteredClientRepository.class);
    
    private final OAuthClientService oauthClientService;
    
    public CouchbaseRegisteredClientRepository(OAuthClientService oauthClientService) {
        this.oauthClientService = oauthClientService;
    }
    
    @Override
    public void save(RegisteredClient registeredClient) {
        // Saving is handled by OAuthClientController -> OAuthClientService
        // This method is called by Spring Authorization Server after token generation,
        // but we don't need to persist anything here since clients are already in Couchbase
        logger.debug("📝 save() called for client: {} (no-op, managed via OAuthClientService)", 
            registeredClient.getClientId());
    }
    
    @Override
    public RegisteredClient findById(String id) {
        logger.debug("🔍 Finding client by ID: {}", id);
        
        Optional<OAuthClient> clientOpt = oauthClientService.getClientById(id);
        if (clientOpt.isEmpty()) {
            logger.debug("❌ Client not found by ID: {}", id);
            return null;
        }
        
        OAuthClient client = clientOpt.get();
        RegisteredClient registered = convertToRegisteredClient(client);
        logger.debug("✅ Found client by ID: {} (name: {})", id, client.getClientName());
        return registered;
    }
    
    @Override
    public RegisteredClient findByClientId(String clientId) {
        logger.debug("🔍 Finding client by clientId: {}", clientId);
        
        Optional<OAuthClient> clientOpt = oauthClientService.getClientById(clientId);
        if (clientOpt.isEmpty()) {
            logger.debug("❌ Client not found by clientId: {}", clientId);
            return null;
        }
        
        OAuthClient client = clientOpt.get();
        
        // Check if client is active
        if (!"active".equals(client.getStatus())) {
            logger.warn("⚠️ Client {} is not active (status: {})", clientId, client.getStatus());
            return null;
        }
        
        RegisteredClient registered = convertToRegisteredClient(client);
        logger.info("✅ Found active client: {} (name: {}, type: {})", 
            clientId, client.getClientName(), client.getClientType());
        return registered;
    }
    
    /**
     * Convert Couchbase OAuthClient to Spring's RegisteredClient
     */
    private RegisteredClient convertToRegisteredClient(OAuthClient client) {
        RegisteredClient.Builder builder = RegisteredClient.withId(client.getClientId())
            .clientId(client.getClientId())
            .clientName(client.getClientName());
        
        // Client authentication method
        if ("confidential".equals(client.getAuthenticationType())) {
            builder.clientSecret(client.getClientSecret()); // Already hashed
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        } else {
            // Public client - no secret
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }
        
        // Authorization grant types
        // System apps use client_credentials, others use authorization_code
        if ("system".equals(client.getClientType())) {
            // System/Backend Service - client_credentials grant (no user interaction)
            builder.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
            logger.debug("🔧 System app: enabling client_credentials grant for {}", client.getClientId());
        } else {
            // Patient/Provider apps - authorization_code grant (interactive)
            builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
            
            // Add refresh token grant if offline_access scope is requested
            if (client.getScopes() != null && client.getScopes().contains("offline_access")) {
                builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
            }
        }
        
        // Redirect URIs
        if (client.getRedirectUris() != null) {
            for (String uri : client.getRedirectUris()) {
                builder.redirectUri(uri);
            }
        }
        
        // Scopes
        if (client.getScopes() != null) {
            for (String scope : client.getScopes()) {
                builder.scope(scope);
            }
        }
        
        // Client settings
        // Only system apps skip consent (no user interaction)
        // Patient/Provider apps and legacy clients (null type) require consent
        boolean requireConsent = !"system".equals(client.getClientType());
        logger.debug("Client {} type='{}' requireConsent={}", 
            client.getClientId(), client.getClientType(), requireConsent);
        
        ClientSettings.Builder clientSettingsBuilder = ClientSettings.builder()
            .requireAuthorizationConsent(requireConsent)
            .requireProofKey(client.isPkceEnabled()); // PKCE requirement
        
        builder.clientSettings(clientSettingsBuilder.build());
        
        // Token settings
        TokenSettings.Builder tokenSettingsBuilder = TokenSettings.builder()
            .accessTokenTimeToLive(Duration.ofHours(1)) // 1 hour access token
            .refreshTokenTimeToLive(Duration.ofDays(30)) // 30 days refresh token
            .reuseRefreshTokens(false); // Issue new refresh token on each refresh
        
        builder.tokenSettings(tokenSettingsBuilder.build());
        
        return builder.build();
    }
}

