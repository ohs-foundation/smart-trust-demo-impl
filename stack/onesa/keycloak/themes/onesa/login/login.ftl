<#import "template.ftl" as layout>
<#import "passkeys.ftl" as passkeys>
<@layout.registrationLayout displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <#if realm.password>
            <#assign invalid = messagesPerField.existsError('username','password')>
            <form id="kc-form-login" class="onesa-form" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                <#if !usernameHidden??>
                    <div class="onesa-field">
                        <label for="username" class="onesa-label"><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></label>
                        <input id="username" class="onesa-input" name="username" value="${(login.username!'')}" type="text"
                               autofocus autocapitalize="none" spellcheck="false" dir="ltr"
                               autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}"
                               <#if invalid>aria-invalid="true" aria-describedby="kc-message"</#if>/>
                    </div>
                </#if>

                <div class="onesa-field">
                    <label for="password" class="onesa-label">${msg("password")}</label>
                    <input id="password" class="onesa-input" name="password" type="password" autocomplete="current-password" dir="ltr"
                           <#if invalid>aria-invalid="true" aria-describedby="kc-message"</#if>/>
                </div>

                <#if realm.rememberMe && !usernameHidden??>
                    <label class="onesa-check">
                        <input id="rememberMe" name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>/> ${msg("rememberMe")}
                    </label>
                </#if>
                <#if realm.resetPasswordAllowed>
                    <a class="onesa-link" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
                </#if>

                <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
            </form>
        </#if>
        <@passkeys.conditionalUIData />
    <#elseif section = "actions">
        <#if realm.password>
            <button class="onesa-btn onesa-btn-primary" form="kc-form-login" name="login" id="kc-login" type="submit">${msg("doLogIn")}</button>
        </#if>
        <p class="onesa-caption">${msg("onesaOfflineNote")}</p>
    <#elseif section = "info">
        <p id="kc-registration">${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a></p>
    <#elseif section = "socialProviders">
        <#if realm.password && social?? && social.providers?has_content>
            <div id="kc-social-providers" class="onesa-social">
                <h2>${msg("identity-provider-login-label")}</h2>
                <#list social.providers as p>
                    <a data-once-link data-disabled-class="is-disabled" id="social-${p.alias}" class="onesa-btn onesa-btn-secondary" href="${p.loginUrl}">${p.displayName!}</a>
                </#list>
            </div>
        </#if>
    </#if>
</@layout.registrationLayout>
