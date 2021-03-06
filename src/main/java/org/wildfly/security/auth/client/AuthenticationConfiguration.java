/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.security.auth.client;

import static org.wildfly.security._private.ElytronMessages.log;

import java.io.IOException;
import java.net.URI;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.ChoiceCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.RealmChoiceCallback;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslClientFactory;
import javax.security.sasl.SaslException;

import org.ietf.jgss.GSSCredential;
import org.wildfly.common.Assert;
import org.wildfly.security.FixedSecurityFactory;
import org.wildfly.security.SecurityFactory;
import org.wildfly.security.credential.Credential;
import org.wildfly.security.credential.GSSKerberosCredential;
import org.wildfly.security.credential.source.CallbackHandlerCredentialSource;
import org.wildfly.security.credential.source.CredentialSource;
import org.wildfly.security.auth.callback.CallbackUtil;
import org.wildfly.security.auth.principal.AnonymousPrincipal;
import org.wildfly.security.auth.principal.NamePrincipal;
import org.wildfly.security.auth.server.IdentityCredentials;
import org.wildfly.security.auth.server.NameRewriter;
import org.wildfly.security.credential.BearerTokenCredential;
import org.wildfly.security.credential.PasswordCredential;
import org.wildfly.security.auth.server.SecurityDomain;
import org.wildfly.security.credential.source.CredentialStoreCredentialSource;
import org.wildfly.security.credential.source.KeyStoreCredentialSource;
import org.wildfly.security.credential.store.CredentialStore;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.interfaces.ClearPassword;
import org.wildfly.security.sasl.localuser.LocalUserSaslFactory;
import org.wildfly.security.sasl.util.FilterMechanismSaslClientFactory;
import org.wildfly.security.sasl.util.PropertiesSaslClientFactory;
import org.wildfly.security.sasl.util.ProtocolSaslClientFactory;
import org.wildfly.security.sasl.util.SaslMechanismInformation;
import org.wildfly.security.sasl.util.SecurityProviderSaslClientFactory;
import org.wildfly.security.sasl.util.ServerNameSaslClientFactory;
import org.wildfly.security.ssl.CipherSuiteSelector;
import org.wildfly.security.ssl.ProtocolSelector;
import org.wildfly.security.ssl.SSLUtils;
import org.wildfly.security.util.ServiceLoaderSupplier;
import org.wildfly.security.credential.X509CertificateChainPrivateCredential;

/**
 * A configuration which controls how authentication is performed.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public abstract class AuthenticationConfiguration {
    // constants

    /**
     * An empty configuration which can be used as the basis for any configuration.  This configuration supports no
     * remapping of any kind, and always uses an anonymous principal.
     */
    public static final AuthenticationConfiguration EMPTY = new AuthenticationConfiguration() {
        void handleCallback(final Callback[] callbacks, final int index) throws UnsupportedCallbackException {
            // always choose the default realm suggestion, or else choose "no realm"
            if (callbacks[index] instanceof RealmCallback) {
                final RealmCallback realmCallback = (RealmCallback) callbacks[index];
                if (realmCallback.getText() != null) {
                    return;
                }
                final String defaultText = realmCallback.getDefaultText();
                if (defaultText != null) {
                    realmCallback.setText(defaultText);
                }
                return;
            } else if (callbacks[index] instanceof RealmChoiceCallback) {
                final RealmChoiceCallback realmChoiceCallback = (RealmChoiceCallback) callbacks[index];
                final int[] selectedIndexes = realmChoiceCallback.getSelectedIndexes();
                if (selectedIndexes == null || selectedIndexes.length == 0) {
                    realmChoiceCallback.setSelectedIndex(realmChoiceCallback.getDefaultChoice());
                }
                return;
            }
            CallbackUtil.unsupported(callbacks[index]);
        }

        void handleCallbacks(final AuthenticationConfiguration config, final Callback[] callbacks) throws IOException, UnsupportedCallbackException {
            final int length = callbacks.length;
            for (int i = 0; i < length; i ++) {
                config.handleCallback(callbacks, i);
            }
        }

        void configureSaslProperties(final Map<String, Object> properties) {
        }

        boolean saslSupportedByConfiguration(final String mechanismName) {
            // always supported by default
            return mechanismName.equals(LocalUserSaslFactory.JBOSS_LOCAL_USER);
        }

        boolean saslAllowedByConfiguration(final String mechanismName) {
            return true;
        }

        String doRewriteUser(final String original) {
            return original;
        }

        AuthenticationConfiguration reparent(final AuthenticationConfiguration newParent) {
            return this;
        }

        AuthenticationConfiguration copyTo(final AuthenticationConfiguration newParent) {
            return newParent;
        }

        AuthenticationConfiguration without(final Class<?> clazz) {
            return this;
        }

        AuthenticationConfiguration without(Class<?> clazz1, Class<?> clazz2) {
            return this;
        }

        AuthenticationConfiguration without(final Class<?> clazz1, final Class<?> clazz2, final Class<?> clazz3) {
            return this;
        }

        String getHost() {
            return null;
        }

        String getProtocol() {
            return null;
        }

        int getPort() {
            return -1;
        }

        Principal getPrincipal() {
            return AnonymousPrincipal.getInstance();
        }

        String getAuthorizationName() {
            return null;
        }

        SSLContext getSslContext() throws NoSuchAlgorithmException {
            return SSLContext.getDefault();
        }

        void configureSslEngine(final SSLEngine sslEngine) {
        }

        void configureSslSocket(final SSLSocket sslSocket) {
        }

        ProtocolSelector getProtocolSelector() {
            return ProtocolSelector.defaultProtocols();
        }

        CipherSuiteSelector getCipherSuiteSelector() {
            return CipherSuiteSelector.openSslDefault();
        }

        SecurityFactory<X509TrustManager> getX509TrustManagerFactory() {
            return SSLUtils.getDefaultX509TrustManagerSecurityFactory();
        }

        SecurityFactory<X509KeyManager> getX509KeyManagerFactory() {
            return null;
        }

        void configureKeyManager(final ConfigurationKeyManager.Builder builder) {
        }

        Supplier<Provider[]> getProviderSupplier() {
            return Security::getProviders;
        }

        @Override
        SaslClientFactory getSaslClientFactory(Supplier<Provider[]> providers) {
            return new SecurityProviderSaslClientFactory(providers);
        }

        boolean delegatesThrough(final Class<?> clazz) {
            return false;
        }

        CredentialSource getCredentialSource() {
            return IdentityCredentials.NONE;
        }

        @Override
        StringBuilder asString(StringBuilder sb) {
            return sb;
        }

        Function<String, String> getNameRewriter() {
            return Function.identity();
        }

        Set<String> getAllowedSaslMechanisms() {
            // this is just for comparison; it doesn't really mean that none are allowed
            return Collections.emptySet();
        }

        Set<String> getDeniedSaslMechanisms() {
            // this is just for comparison; it doesn't really mean that none are denied
            return Collections.emptySet();
        }

        Predicate<ChoiceCallback> getChoiceOperation() {
            return c -> false;
        }

        SecurityDomain getForwardSecurityDomain() {
            return null;
        }

        AccessControlContext getForwardAccessControlContext() {
            return null;
        }

        Map<String, String> getMechanismProperties() {
            return null;
        }

        List<AlgorithmParameterSpec> getParameterSpecs() {
            return Collections.emptyList();
        }

        String getMechanismRealm() {
            return null;
        }

        Supplier<SaslClientFactory> getSaslClientFactorySupplier() {
            return null;
        }

        boolean halfEqual(AuthenticationConfiguration other) {
            return true;
        }

        public String toString() {
            return "";
        }

        int calcHashCode() {
            return System.identityHashCode(this);
        }
    }.useAnonymous().useTrustManager(null).forbidSaslMechanisms(SaslMechanismInformation.Names.EXTERNAL);

    private final AuthenticationConfiguration parent;
    private SaslClientFactory saslClientFactory = null;
    private int hashCode;

    // constructors

    AuthenticationConfiguration() {
        this.parent = null;
    }

    AuthenticationConfiguration(final AuthenticationConfiguration parent) {
        this.parent = parent.without(getClass());
    }

    AuthenticationConfiguration(final AuthenticationConfiguration parent, final boolean allowMultiple) {
        this.parent = allowMultiple ? parent : parent.without(getClass());
    }

    // test method

    Principal getPrincipal() {
        return parent.getPrincipal();
    }

    String getHost() {
        return parent.getHost();
    }

    String getProtocol() {
        return parent.getProtocol();
    }

    int getPort() {
        return parent.getPort();
    }

    // internal actions

    void handleCallback(Callback[] callbacks, int index) throws IOException, UnsupportedCallbackException {
        parent.handleCallback(callbacks, index);
    }

    void handleCallbacks(AuthenticationConfiguration config, Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        parent.handleCallbacks(config, callbacks);
    }

    void configureSaslProperties(Map<String, Object> properties) {
        parent.configureSaslProperties(properties);
    }

    /**
     * Determine if this SASL mechanism is supported by this configuration (not policy).  Implementations must
     * combine using boolean-OR operations.
     *
     * @param mechanismName the mech name (must not be {@code null})
     * @return {@code true} if supported, {@code false} otherwise
     */
    boolean saslSupportedByConfiguration(String mechanismName) {
        return parent.saslSupportedByConfiguration(mechanismName);
    }

    /**
     * Determine if this SASL mechanism is allowed by this configuration's policy.  Implementations must combine
     * using boolean-AND operations.
     *
     * @param mechanismName the mech name (must not be {@code null})
     * @return {@code true} if allowed, {@code false} otherwise
     */
    boolean saslAllowedByConfiguration(String mechanismName) {
        return parent.saslAllowedByConfiguration(mechanismName);
    }

    final boolean filterOneSaslMechanism(String mechanismName) {
        return saslSupportedByConfiguration(mechanismName) && saslAllowedByConfiguration(mechanismName);
    }

    String doRewriteUser(String original) {
        return parent.doRewriteUser(original);
    }

    String getAuthorizationName() {
        return parent.getAuthorizationName();
    }

    SSLContext getSslContext() throws GeneralSecurityException {
        return parent.getSslContext();
    }

    void configureSslEngine(final SSLEngine sslEngine) {
        parent.configureSslEngine(sslEngine);
    }

    void configureSslSocket(final SSLSocket sslSocket) {
        parent.configureSslSocket(sslSocket);
    }

    ProtocolSelector getProtocolSelector() {
        return parent.getProtocolSelector();
    }

    CipherSuiteSelector getCipherSuiteSelector() {
        return parent.getCipherSuiteSelector();
    }

    Supplier<Provider[]> getProviderSupplier() {
        return parent.getProviderSupplier();
    }

    SaslClientFactory getSaslClientFactory(Supplier<Provider[]> providers) {
        return parent.getSaslClientFactory(providers);
    }

    SecurityFactory<X509TrustManager> getX509TrustManagerFactory() {
        return parent.getX509TrustManagerFactory();
    }

    SecurityFactory<X509KeyManager> getX509KeyManagerFactory() {
        return parent.getX509KeyManagerFactory();
    }

    void configureKeyManager(ConfigurationKeyManager.Builder builder) throws GeneralSecurityException {
        parent.configureKeyManager(builder);
    }

    CredentialSource getCredentialSource() {
        return parent.getCredentialSource();
    }

    abstract AuthenticationConfiguration reparent(AuthenticationConfiguration newParent);

    AuthenticationConfiguration copyTo(AuthenticationConfiguration newParent) {
        return reparent(parent.copyTo(newParent));
    }

    AuthenticationConfiguration without(Class<?> clazz) {
        AuthenticationConfiguration newParent = parent.without(clazz);
        if (clazz.isInstance(this)) return newParent;
        if (parent == newParent) return this;
        return reparent(newParent);
    }

    AuthenticationConfiguration without(Class<?> clazz1, Class<?> clazz2) {
        AuthenticationConfiguration newParent = parent.without(clazz1, clazz2);
        if (clazz1.isInstance(this) || clazz2.isInstance(this)) return newParent;
        if (parent == newParent) return this;
        return reparent(newParent);
    }

    AuthenticationConfiguration without(Class<?> clazz1, Class<?> clazz2, Class<?> clazz3) {
        AuthenticationConfiguration newParent = parent.without(clazz1, clazz2, clazz3);
        if (clazz1.isInstance(this) || clazz2.isInstance(this) || clazz3.isInstance(this)) return newParent;
        if (parent == newParent) return this;
        return reparent(newParent);
    }

    boolean delegatesThrough(Class<?> clazz) {
        return clazz.isInstance(this) || parent.delegatesThrough(clazz);
    }

    // assembly methods - rewrite

    /**
     * Create a new configuration which is the same as this configuration, but rewrites the user name using the given
     * name rewriter.  The name rewriter is appended to the the existing name rewrite function.
     *
     * @param rewriter the name rewriter
     * @return the new configuration
     */
    public final AuthenticationConfiguration rewriteUser(NameRewriter rewriter) {
        if (rewriter == null) {
            return this;
        }
        return new RewriteNameAuthenticationConfiguration(this, getNameRewriter().andThen(rewriter::rewriteName));
    }

    /**
     * Create a new configuration which is the same as this configuration, but rewrites the user name using <em>only</em>
     * the given name rewriter.  Any name rewriters on this configuration are ignored for the new configuration.
     *
     * @param rewriter the name rewriter
     * @return the new configuration
     */
    public final AuthenticationConfiguration rewriteUserOnlyWith(NameRewriter rewriter) {
        if (rewriter == null) {
            return this;
        }
        return new RewriteNameAuthenticationConfiguration(this, rewriter::rewriteName);
    }

    // assembly methods - filter

    // assembly methods - configuration

    /**
     * Create a new configuration which is the same as this configuration, but which uses an anonymous login.
     *
     * @return the new configuration
     */
    public final AuthenticationConfiguration useAnonymous() {
        return new SetAnonymousAuthenticationConfiguration(this);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given principal to authenticate.
     *
     * @param principal the principal to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration usePrincipal(NamePrincipal principal) {
        return new SetNamePrincipalAuthenticationConfiguration(this, principal);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given login name to authenticate.
     *
     * @param name the principal to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration useName(String name) {
        return usePrincipal(new NamePrincipal(name));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which attempts to authorize to the given
     * name after authentication.
     *
     * @param name the name to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration useAuthorizationName(String name) {
        return new SetAuthorizationNameAuthenticationConfiguration(this, name);
    }

    public final AuthenticationConfiguration useCredential(Credential credential) {
        if (credential == null) return this;
        final CredentialSource credentialSource = getCredentialSource();
        if (credentialSource instanceof IdentityCredentials) {
            return new SetCredentialsConfiguration(this, ((IdentityCredentials) credentialSource).withCredential(credential));
        } else {
            return new SetCredentialsConfiguration(this, credentialSource.with(IdentityCredentials.NONE.withCredential(credential)));
        }
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given password to authenticate.
     *
     * @param password the password to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration usePassword(Password password) {
        final CredentialSource filtered = getCredentialSource().without(PasswordCredential.class);
        return password == null ? useCredentials(filtered) : useCredentials(filtered).useCredential(new PasswordCredential(password));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given password to authenticate.
     *
     * @param password the password to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration usePassword(char[] password) {
        return usePassword(password == null ? null : ClearPassword.createRaw(ClearPassword.ALGORITHM_CLEAR, password));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given password to authenticate.
     *
     * @param password the password to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration usePassword(String password) {
        return usePassword(password == null ? null : ClearPassword.createRaw(ClearPassword.ALGORITHM_CLEAR, password.toCharArray()));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given password to authenticate.
     *
     * @param password the password to use
     * @param matchPredicate the predicate to determine if a password callback prompt is relevant for the given password or
     *                       {@code null} to use the given password regardless of the prompt
     * @return the new configuration
     */
    public final AuthenticationConfiguration usePassword(Password password, Predicate<String> matchPredicate) {
        return usePassword(password);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given password to authenticate.
     *
     * @param password the password to use
     * @param matchPredicate the predicate to determine if a password callback prompt is relevant for the given password or
     *                       {@code null} to use the given password regardless of the prompt
     * @return the new configuration
     */
    public final AuthenticationConfiguration usePassword(char[] password, Predicate<String> matchPredicate) {
        return usePassword(password);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given password to authenticate.
     *
     * @param password the password to use
     * @param matchPredicate the predicate to determine if a password callback prompt is relevant for the given password or
     *                       {@code null} to use the given password regardless of the prompt
     * @return the new configuration
     */
    public final AuthenticationConfiguration usePassword(String password, Predicate<String> matchPredicate) {
        return usePassword(password);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given callback handler to
     * acquire a password with which to authenticate, when a password-based authentication algorithm is in use.
     *
     * @param callbackHandler the password callback handler
     * @return the new configuration
     */
    public final AuthenticationConfiguration useCredentialCallbackHandler(CallbackHandler callbackHandler) {
        return callbackHandler == null ? this : useCredentials(new CallbackHandlerCredentialSource(callbackHandler).with(getCredentialSource()));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given callback handler
     * to authenticate.
     * <p>
     * <em>Important notes:</em> It is important to ensure that each distinct client identity uses a distinct {@code CallbackHandler}
     * instance in order to avoid mis-pooling of connections, identity crossovers, and other potentially serious problems.
     * It is not recommended that a {@code CallbackHandler} implement {@code equals()} and {@code hashCode()}, however if it does,
     * it is important to ensure that these methods consider equality based on an authenticating identity that does not
     * change between instances.  In particular, a callback handler which requests user input on each usage is likely to cause
     * a problem if the user name can change on each authentication request.
     * <p>
     * Because {@code CallbackHandler} instances are unique per identity, it is often useful for instances to cache
     * identity information, credentials, and/or other authentication-related information in order to facilitate fast
     * re-authentication.
     *
     * @param callbackHandler the callback handler to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration useCallbackHandler(CallbackHandler callbackHandler) {
        return callbackHandler == null ? this : new SetCallbackHandlerAuthenticationConfiguration(this, callbackHandler);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given GSS-API credential to authenticate.
     *
     * @param credential the GSS-API credential to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration useGSSCredential(GSSCredential credential) {
        return credential == null ? this : useCredential(new GSSKerberosCredential(credential));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given key store and alias
     * to acquire the credential required for authentication.
     *
     * @param keyStoreEntry the key store entry to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration useKeyStoreCredential(KeyStore.Entry keyStoreEntry) {
        return keyStoreEntry == null ? this : useCredentials(getCredentialSource().with(new KeyStoreCredentialSource(new FixedSecurityFactory<>(keyStoreEntry))));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given key store and alias
     * to acquire the credential required for authentication.
     *
     * @param keyStore the key store to use
     * @param alias the key store alias
     * @return the new configuration
     */
    public final AuthenticationConfiguration useKeyStoreCredential(KeyStore keyStore, String alias) {
        return keyStore == null || alias == null ? this : useCredentials(getCredentialSource().with(new KeyStoreCredentialSource(keyStore, alias, null)));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given key store and alias
     * to acquire the credential required for authentication.
     *
     * @param keyStore the key store to use
     * @param alias the key store alias
     * @param protectionParameter the protection parameter to use to access the key store entry
     * @return the new configuration
     */
    public final AuthenticationConfiguration useKeyStoreCredential(KeyStore keyStore, String alias, KeyStore.ProtectionParameter protectionParameter) {
        return keyStore == null || alias == null ? this : useCredentials(getCredentialSource().with(new KeyStoreCredentialSource(keyStore, alias, protectionParameter)));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given private key and X.509
     * certificate chain to authenticate.
     *
     * @param privateKey the client private key
     * @param certificateChain the client certificate chain
     * @return the new configuration
     */
    public final AuthenticationConfiguration useCertificateCredential(PrivateKey privateKey, X509Certificate... certificateChain) {
        return certificateChain == null || certificateChain.length == 0 || privateKey == null ? this : useCertificateCredential(new X509CertificateChainPrivateCredential(privateKey, certificateChain));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given private key and X.509
     * certificate chain to authenticate.
     *
     * @param credential the credential containing the private key and certificate chain
     * @return the new configuration
     */
    public final AuthenticationConfiguration useCertificateCredential(X509CertificateChainPrivateCredential credential) {
        return credential == null ? this : useCredential(credential);
    }

    /**
     * Create a new configuration which is the same as this configuration, but uses credentials found at the given
     * alias and credential store.
     *
     * @param credentialStore the credential store (must not be {@code null})
     * @param alias the alias within the store (must not be {@code null})
     * @return the new configuration
     */
    public final AuthenticationConfiguration useCredentialStoreEntry(CredentialStore credentialStore, String alias) {
        Assert.checkNotNullParam("credentialStore", credentialStore);
        Assert.checkNotNullParam("alias", alias);
        CredentialStoreCredentialSource csCredentialSource = new CredentialStoreCredentialSource(credentialStore, alias);
        return useCredentials(getCredentialSource().with(csCredentialSource));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given key manager
     * to acquire the credential required for authentication.
     *
     * @param keyManager the key manager to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration useKeyManagerCredential(X509KeyManager keyManager) {
        return keyManager == null ? without(SetKeyManagerCredentialAuthenticationConfiguration.class) : new SetKeyManagerCredentialAuthenticationConfiguration(this, new FixedSecurityFactory<>(keyManager));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given identity
     * credentials to acquire the credential required for authentication.
     *
     * @param credentials the credentials to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration useCredentials(CredentialSource credentials) {
        return credentials == null ? without(SetCredentialsConfiguration.class) : new SetCredentialsConfiguration(this, credentials);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given identity
     * credentials to acquire the credential required for authentication.
     *
     * @param credentials the credentials to use
     * @param matchPredicate the predicate to determine if a callback prompt is relevant for the given credentials or
     *                       {@code null} to use the given credentials regardless of the prompt
     * @return the new configuration
     */
    public final AuthenticationConfiguration useCredentials(CredentialSource credentials, Predicate<String> matchPredicate) {
        return useCredentials(credentials);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given choice if the given
     * predicate evaluates to {@code true}.
     *
     * @param matchPredicate the predicate that should be used to determine if a choice callback type and prompt are
     *                       relevant for the given choice
     * @param choice the choice to use if the given predicate evaluates to {@code true}
     * @return the new configuration
     */
    public final AuthenticationConfiguration useChoice(BiPredicate<Class<? extends ChoiceCallback>, String> matchPredicate, String choice) {
        return matchPredicate == null ? this : new SetChoiceAuthenticationConfiguration(this, getChoiceOperation().or(c -> {
            if (matchPredicate.test(c.getClass(), c.getPrompt())) {
                //TODO handle multiple selections etc.
                if (choice == null) {
                    c.setSelectedIndex(c.getDefaultChoice());
                    return true;
                } else {
                    String[] choices = c.getChoices();
                    for (int i = 0; i < choices.length; i++) {
                        if (choice.equals(choices[i])) {
                            c.setSelectedIndex(i);
                            return true;
                        }
                    }
                }
            }
            return false;
        }));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given parameter specification.
     *
     * @param parameterSpec the algorithm parameter specification to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration useParameterSpec(AlgorithmParameterSpec parameterSpec) {
        if (parameterSpec == null) {
            return this;
        }
        final List<AlgorithmParameterSpec> specs = getParameterSpecs();
        if (specs.isEmpty()) {
            return new SetParameterSpecAuthenticationConfiguration(this, Collections.singletonList(parameterSpec));
        } else {
            ArrayList<AlgorithmParameterSpec> newList = new ArrayList<>();
            for (AlgorithmParameterSpec spec : specs) {
                if (spec.getClass() == parameterSpec.getClass()) continue;
                newList.add(spec);
            }
            if (newList.isEmpty()) {
                return new SetParameterSpecAuthenticationConfiguration(this, Collections.singletonList(parameterSpec));
            } else {
                newList.add(parameterSpec);
                return new SetParameterSpecAuthenticationConfiguration(this, newList);
            }
        }
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given trust manager
     * for trust verification.
     *
     * @param trustManager the trust manager to use or {@code null} if the default trust manager should be used
     * @return the new configuration
     */
    public final AuthenticationConfiguration useTrustManager(X509TrustManager trustManager) {
        return trustManager == null ? new SetTrustManagerAuthenticationConfiguration(this, SSLUtils.getDefaultX509TrustManagerSecurityFactory()) : new SetTrustManagerAuthenticationConfiguration(this, new FixedSecurityFactory<>(trustManager));
    }

    /**
     * Create a new configuration which is the same as this configuration, but which connects to a different host name.
     *
     * @param hostName the host name to connect to
     * @return the new configuration
     */
    public final AuthenticationConfiguration useHost(String hostName) {
        if (hostName == null || hostName.isEmpty()) {
            return without(SetHostAuthenticationConfiguration.class);
        }
        return new SetHostAuthenticationConfiguration(this, hostName);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which specifies a different protocol to be passed to the authentication mechanisms.
     *
     * @param protocol the protocol to pass to the authentication mechanisms.
     * @return the new configuration
     */
    public final AuthenticationConfiguration useProtocol(String protocol) {
        if (protocol == null || protocol.isEmpty()) {
            return without(SetProtocolAuthenticationConfiguration.class);
        }
        return new SetProtocolAuthenticationConfiguration(this, protocol);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which connects to a different port.
     *
     * @param port the port to connect to
     * @return the new configuration
     */
    public final AuthenticationConfiguration usePort(int port) {
        if (port < 1 || port > 65535) throw log.invalidPortNumber(port);
        return new SetPortAuthenticationConfiguration(this, port);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which forwards the authentication name
     * and credentials from the current identity of the given security domain.
     *
     * @param securityDomain the security domain (must not be {@code null})
     * @return the new configuration
     */
    public final AuthenticationConfiguration useForwardedIdentity(SecurityDomain securityDomain) {
        Assert.checkNotNullParam("securityDomain", securityDomain);
        final AccessControlContext context = AccessController.getContext();
        return new SetForwardAuthenticationConfiguration(this, securityDomain, context);
    }

    // Providers

    /**
     * Use the given security provider supplier to locate security implementations.
     *
     * @param providerSupplier the provider supplier
     * @return the new configuration
     */
    public final AuthenticationConfiguration useProviders(Supplier<Provider[]> providerSupplier) {
        return providerSupplier == null ? useDefaultProviders() : new ProvidersAuthenticationConfiguration(this, providerSupplier);
    }

    /**
     * Use the system default security providers to locate security implementations.
     *
     * @return the new configuration
     */
    public final AuthenticationConfiguration useDefaultProviders() {
        return without(ProvidersAuthenticationConfiguration.class);
    }

    /**
     * Use security providers from the given class loader.
     *
     * @param classLoader the class loader to search for security providers
     * @return the new configuration
     */
    public final AuthenticationConfiguration useProvidersFromClassLoader(ClassLoader classLoader) {
        return useProviders(new ServiceLoaderSupplier<Provider>(Provider.class, classLoader));
    }

    // SASL Mechanisms

    /**
     * Use a pre-existing {@link SaslClientFactory} instead of discovery.
     *
     * @param saslClientFactory the pre-existing {@link SaslClientFactory} to use.
     * @return the new configuration.
     */
    public final AuthenticationConfiguration useSaslClientFactory(final SaslClientFactory saslClientFactory) {
        return useSaslClientFactory(() -> saslClientFactory);
    }

    /**
     * Use the given sasl client factory supplier to obtain the {@link SaslClientFactory} to use.
     *
     * @param saslClientFactory the sasl client factory supplier to use.
     * @return the new configuration.
     */
    public final AuthenticationConfiguration useSaslClientFactory(final Supplier<SaslClientFactory> saslClientFactory) {
        return new SetSaslClientFactoryAuthenticationConfiguration(this, saslClientFactory);
    }

    /**
     * Use provider based discovery to load available {@link SaslClientFactory} implementations.
     *
     * @return the new configuration.
     */
    public final AuthenticationConfiguration useSaslClientFactoryFromProviders() {
        return without(SetSaslClientFactoryAuthenticationConfiguration.class);
    }

    // SASL Configuration

    /**
     * Create a new configuration which is the same as this configuration, but which sets the properties that will be passed to
     * the {@code SaslClientFactory} when the mechanism is created.
     *
     * @param mechanismProperties the properties to be passed to the {@code SaslClientFactory} to create the mechanism.
     * @return the new configuration.
     */
    public final AuthenticationConfiguration useMechanismProperties(Map<String, String> mechanismProperties) {
        return mechanismProperties == null || mechanismProperties.isEmpty() ? this : new SetMechanismPropertiesConfiguration(this, mechanismProperties);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which allows all SASL mechanisms.
     *
     * @return the new configuration.
     */
    public final AuthenticationConfiguration allowAllSaslMechanisms() {
        return without(FilterSaslMechanismAuthenticationConfiguration.class);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which allows only the given named mechanisms.
     *
     * @param names the mechanism names
     * @return the new configuration
     */
    public final AuthenticationConfiguration allowSaslMechanisms(String... names) {
        final List<String> namesList = names == null || names.length == 0 ? Collections.emptyList() : Arrays.asList(names);
        final Set<String> allowedSaslMechanisms = getAllowedSaslMechanisms();
        final Set<String> deniedSaslMechanisms = getDeniedSaslMechanisms();
        final Set<String> newAllowed = new HashSet<>(allowedSaslMechanisms);
        newAllowed.addAll(namesList);
        final Set<String> newDenied = new HashSet<>(deniedSaslMechanisms);
        newDenied.removeAll(namesList);
        return newAllowed.isEmpty() && newDenied.isEmpty() ? allowAllSaslMechanisms() : new FilterSaslMechanismAuthenticationConfiguration(this, newAllowed, newDenied);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which forbids the given named mechanisms.
     *
     * @param names the mechanism names
     * @return the new configuration
     */
    public final AuthenticationConfiguration forbidSaslMechanisms(String... names) {
        final List<String> namesList = names == null || names.length == 0 ? Collections.emptyList() : Arrays.asList(names);
        final Set<String> allowedSaslMechanisms = getAllowedSaslMechanisms();
        final Set<String> deniedSaslMechanisms = getDeniedSaslMechanisms();
        final Set<String> newAllowed = new HashSet<>(allowedSaslMechanisms);
        newAllowed.removeAll(namesList);
        final Set<String> newDenied = new HashSet<>(deniedSaslMechanisms);
        newDenied.addAll(namesList);
        return newAllowed.isEmpty() && newDenied.isEmpty() ? allowAllSaslMechanisms() : new FilterSaslMechanismAuthenticationConfiguration(this, newAllowed, newDenied);
    }

    // other

    public final AuthenticationConfiguration useRealm(String realm) {
        return realm == null ? without(SetRealmAuthenticationConfiguration.class) : new SetRealmAuthenticationConfiguration(this, realm);
    }

    /**
     * Create a new configuration which is the same as this configuration, but which uses the given {@link BearerTokenCredential} to authenticate.
     *
     * @param credential the bearer token credential to use
     * @return the new configuration
     */
    public final AuthenticationConfiguration useBearerTokenCredential(BearerTokenCredential credential) {
        return credential == null ? this : useCredentials(getCredentialSource().with(IdentityCredentials.NONE.withCredential(credential)));
    }

    // merging

    /**
     * Create a new configuration which is the same as this configuration, but which adds or replaces every item in the
     * {@code other} configuration with that item, overwriting any corresponding such item in this configuration.
     *
     * @param other the other authentication configuration
     * @return the merged authentication configuration
     */
    public final AuthenticationConfiguration with(AuthenticationConfiguration other) {
        return other.copyTo(this);
    }

    // client methods

    CallbackHandler getCallbackHandler() {
        return null;
    }

    /**
     * Get the {@link SaslClientFactory} for this factory, either using a cached instance or creating if required.
     * @return
     */
    private SaslClientFactory getSaslClientFactory() {
        if (saslClientFactory == null) {
            synchronized (this) {
                if (saslClientFactory == null) {
                    saslClientFactory = getSaslClientFactory(getProviderSupplier());
                }
            }
        }
        return saslClientFactory;
    }

    final SaslClient createSaslClient(URI uri, Collection<String> serverMechanisms, UnaryOperator<SaslClientFactory> factoryOperator) throws SaslException {
        SaslClientFactory saslClientFactory = factoryOperator.apply(getSaslClientFactory());

        final HashMap<String, Object> properties = new HashMap<String, Object>();
        configureSaslProperties(properties);
        if (properties.isEmpty() == false) {
            saslClientFactory = new PropertiesSaslClientFactory(saslClientFactory, properties);
        }
        String host = getHost();
        if (host != null) {
            saslClientFactory = new ServerNameSaslClientFactory(saslClientFactory, host);
        }
        String protocol = getProtocol();
        if (protocol != null) {
            saslClientFactory = new ProtocolSaslClientFactory(saslClientFactory, protocol);
        }
        saslClientFactory = new FilterMechanismSaslClientFactory(saslClientFactory, this::filterOneSaslMechanism);

        final CallbackHandler callbackHandler = getCallbackHandler();
        return saslClientFactory.createSaslClient(serverMechanisms.toArray(new String[serverMechanisms.size()]),
                getAuthorizationName(), uri.getScheme(), uri.getHost(), Collections.emptyMap(), callbackHandler == null ? this::defaultHandleCallbacks : callbackHandler);
    }

    void defaultHandleCallbacks(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        handleCallbacks(this, callbacks);
    }

    // equality

    /**
     * Determine whether this configuration is equal to another object.  Two configurations are equal if they
     * apply the same items.
     *
     * @param obj the other object
     * @return {@code true} if they are equal, {@code false} otherwise
     */
    public final boolean equals(final Object obj) {
        return obj instanceof AuthenticationConfiguration && equals((AuthenticationConfiguration) obj);
    }

    /**
     * Determine whether this configuration is equal to another object.  Two configurations are equal if they
     * apply the same items.
     *
     * @param other the other object
     * @return {@code true} if they are equal, {@code false} otherwise
     */
    public final boolean equals(final AuthenticationConfiguration other) {
        return hashCode() == other.hashCode() && halfEqual(other) && other.halfEqual(this);
    }

    abstract boolean halfEqual(final AuthenticationConfiguration other);

    final boolean parentHalfEqual(final AuthenticationConfiguration other) {
        return parent.halfEqual(other);
    }

    abstract int calcHashCode();

    /**
     * Get the hash code of this authentication configuration.
     *
     * @return the hash code of this authentication configuration
     */
    public int hashCode() {
        int hashCode = this.hashCode;
        if (hashCode == 0) {
            hashCode = calcHashCode();
            if (hashCode == 0) {
                hashCode = 1;
            }
            this.hashCode = hashCode;
        }
        return hashCode;
    }

    final int parentHashCode() {
        return parent.hashCode();
    }

    // String Representation

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        asString(sb);
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    abstract StringBuilder asString(StringBuilder sb);

    final StringBuilder parentAsString(StringBuilder sb) {
        return parent.asString(sb);
    }

    // delegates for equality tests

    Function<String, String> getNameRewriter() {
        return parent.getNameRewriter();
    }

    Set<String> getAllowedSaslMechanisms() {
        return parent.getAllowedSaslMechanisms();
    }

    Set<String> getDeniedSaslMechanisms() {
        return parent.getDeniedSaslMechanisms();
    }

    Predicate<ChoiceCallback> getChoiceOperation() {
        return parent.getChoiceOperation();
    }

    SecurityDomain getForwardSecurityDomain() {
        return parent.getForwardSecurityDomain();
    }

    AccessControlContext getForwardAccessControlContext() {
        return parent.getForwardAccessControlContext();
    }

    Map<String, String> getMechanismProperties() {
        return parent.getMechanismProperties();
    }

    List<AlgorithmParameterSpec> getParameterSpecs() {
        return parent.getParameterSpecs();
    }

    String getMechanismRealm() {
        return parent.getMechanismRealm();
    }

    Supplier<SaslClientFactory> getSaslClientFactorySupplier() {
        return parent.getSaslClientFactorySupplier();
    }

    // interfaces

    interface UserSetting extends HandlesCallbacks {}
    interface CredentialSetting extends HandlesCallbacks {}
    interface HandlesCallbacks {}
}
