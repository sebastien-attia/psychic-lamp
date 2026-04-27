<#import "template.ftl" as layout>
<@layout.registrationLayout
    displayMessage=!messagesPerField.existsError('username','password')
    displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??
    ; section>
    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <#if realm.password>
            <form id="kc-form-login" action="${url.loginAction}" method="post" novalidate>
                <#if !usernameHidden??>
                    <div class="form-row">
                        <label for="username">
                            <#if !realm.loginWithEmailAllowed>${msg("username")}
                            <#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}
                            <#else>${msg("email")}</#if>
                        </label>
                        <input tabindex="1" id="username" name="username"
                               class="kc-input"
                               value="${(login.username!'')}" type="text"
                               autofocus autocomplete="username"
                               aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"/>
                        <#if messagesPerField.existsError('username','password')>
                            <span class="kc-feedback-error" aria-live="polite">
                                ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                            </span>
                        </#if>
                    </div>
                </#if>

                <div class="form-row">
                    <label for="password">${msg("password")}</label>
                    <input tabindex="2" id="password" name="password"
                           class="kc-input"
                           type="password" autocomplete="current-password"
                           aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"/>
                </div>

                <#if realm.rememberMe && !usernameHidden??>
                    <div class="form-row form-row-checkbox">
                        <label for="rememberMe">
                            <input tabindex="3" id="rememberMe" name="rememberMe"
                                   type="checkbox"
                                   <#if login.rememberMe??>checked</#if>>
                            ${msg("rememberMe")}
                        </label>
                    </div>
                </#if>

                <div class="form-row">
                    <input type="hidden" id="id-hidden-input" name="credentialId"
                        <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if> />
                    <button tabindex="4" class="kc-button-primary" name="login"
                            id="kc-login" type="submit">
                        ${msg("doLogIn")}
                    </button>
                </div>

                <#if realm.resetPasswordAllowed>
                    <div class="form-row form-row-secondary">
                        <a tabindex="5" href="${url.loginResetCredentialsUrl}">
                            ${msg("doForgotPassword")}
                        </a>
                    </div>
                </#if>
            </form>
        </#if>
    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <span>${msg("noAccount")}
                <a tabindex="6" href="${url.registrationUrl}">${msg("doRegister")}</a>
            </span>
        </#if>
    </#if>
</@layout.registrationLayout>
