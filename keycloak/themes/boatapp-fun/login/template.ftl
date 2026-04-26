<#--
    Boat App custom Keycloak login template.

    Overrides the `base` theme's `registrationLayout` macro so every
    login-flow page (login, password reset, OTP, etc.) sits inside our
    own card on top of the random colour split. The macro is invoked by
    the page-level `*.ftl` files (e.g. `login.ftl`) via `<#nested>`.

    Localised messages come from `messages/messages_<locale>.properties`
    and the upstream `common/keycloak` bundle.
-->
<#macro registrationLayout displayInfo=false displayMessage=true displayRequiredFields=false showAnotherWayIfPresent=true>
<!DOCTYPE html>
<html lang="${(locale.currentLanguageTag)!'en'}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>${msg("loginTitle",(realm.displayName!''))}</title>
    <link rel="icon" href="${url.resourcesPath}/img/boat-glyph.svg" type="image/svg+xml">
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript" defer></script>
        </#list>
    </#if>
</head>
<body>
    <div class="boatapp-split-bg" aria-hidden="true"></div>

    <button id="shuffle-btn" type="button" class="shuffle-btn"
            aria-label="${msg('doShuffle')}" title="${msg('doShuffle')}">
        <span aria-hidden="true">&#x1F500;</span>
    </button>

    <main class="boatapp-stage">
        <section class="login-card" aria-labelledby="login-title">
            <header class="login-header">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="40" height="40" aria-hidden="true" focusable="false">
                    <path d="M4 34 L44 34 L36 42 L12 42 Z" fill="#0038B8"/>
                    <rect x="23" y="6" width="2" height="28" fill="#0038B8"/>
                    <path d="M25 10 L40 32 L25 32 Z" fill="#94B800"/>
                    <path d="M23 6 L32 9 L23 12 Z" fill="#B82200"/>
                </svg>
                <h1 id="login-title" class="login-title">
                    <#nested "header">
                </h1>
            </header>

            <#-- Inline feedback (errors, info). Skip warnings during AIA so
                 the dialog doesn't stack a banner on top of the form copy. -->
            <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                <div class="alert alert-${message.type}" role="alert">
                    <span class="kc-feedback-text">${kcSanitize(message.summary)?no_esc}</span>
                </div>
            </#if>

            <#nested "form">

            <#if displayInfo>
                <div class="login-info">
                    <#nested "info">
                </div>
            </#if>
        </section>
    </main>
</body>
</html>
</#macro>
