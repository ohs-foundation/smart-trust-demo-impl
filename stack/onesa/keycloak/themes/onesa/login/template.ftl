<#macro icon paths>
<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths?no_esc}</svg>
</#macro>
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html lang="${lang}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="color-scheme" content="light">
    <meta name="robots" content="noindex, nofollow">
    <title>${msg("loginTitle",(realm.displayName!''))}</title>
    <link rel="icon" href="${url.resourcesPath}/img/favicon.svg" type="image/svg+xml">
    <#list properties.styles?split(' ') as style>
        <link href="${url.resourcesPath}/${style}" rel="stylesheet">
    </#list>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module">
        import { startSessionPolling } from "${url.resourcesPath}/js/authChecker.js";

        startSessionPolling(
            "${url.ssoLoginInOtherTabsUrl?no_esc}"
        );
    </script>
    <script type="module">
        document.addEventListener("click", (event) => {
            const link = event.target.closest("a[data-once-link]");

            if (!link) {
                return;
            }

            if (link.getAttribute("aria-disabled") === "true") {
                event.preventDefault();
                return;
            }

            const { disabledClass } = link.dataset;

            if (disabledClass) {
                link.classList.add(...disabledClass.trim().split(/\s+/));
            }

            link.setAttribute("role", "link");
            link.setAttribute("aria-disabled", "true");
        });
    </script>
    <#if authenticationSession??>
        <script type="module">
            import { checkAuthSession } from "${url.resourcesPath}/js/authChecker.js";

            checkAuthSession(
                "${authenticationSession.authSessionIdHash}"
            );
        </script>
    </#if>
</head>

<body class="onesa ${bodyClass}" data-page-id="login-${pageId}">
    <header class="onesa-bar">
        <span class="onesa-mark"><img src="${url.resourcesPath}/img/favicon.svg" alt=""></span>
        <div class="onesa-bar-t">
            <b>${realm.displayName!''}</b>
            <small>${msg("onesaAuthority")}</small>
        </div>
    </header>

    <main class="onesa-main">
        <p class="onesa-note"><@icon '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>'/>${msg("onesaAudience")}</p>

        <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
            <div id="kc-message" class="onesa-alert onesa-alert-${message.type}" role="<#if message.type = 'error'>alert<#else>status</#if>">
                <#if message.type = 'error' || message.type = 'warning'>
                    <@icon '<path d="M12 3L2 21h20zM12 10v4M12 17h.01"/>'/>
                <#else>
                    <@icon '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>'/>
                </#if>
                <p>${kcSanitize(message.summary)?no_esc}</p>
            </div>
        </#if>

        <section class="onesa-card" aria-labelledby="kc-page-title">
            <h1 id="kc-page-title"><#nested "header"></h1>

            <#if auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
                <#nested "show-username">
                <p id="kc-username" class="onesa-attempted">
                    <span id="kc-attempted-username">${auth.attemptedUsername}</span>
                    <a id="reset-login" href="${url.loginRestartFlowUrl}">${msg("restartLoginTooltip")}</a>
                </p>
            </#if>

            <#if displayRequiredFields>
                <p class="onesa-required"><span class="required">*</span> ${msg("requiredFields")}</p>
            </#if>

            <#nested "form">

            <#if auth?has_content && auth.showTryAnotherWayLink()>
                <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
                    <input type="hidden" name="tryAnotherWay" value="on"/>
                    <a href="#" id="try-another-way"
                       onclick="document.forms['kc-select-try-another-way-form'].requestSubmit();return false;">${msg("doTryAnotherWay")}</a>
                </form>
            </#if>

            <#nested "socialProviders">

            <#if displayInfo>
                <div id="kc-info" class="onesa-info">
                    <#nested "info">
                </div>
            </#if>
        </section>
    </main>

    <#local actions><#nested "actions"></#local>
    <#if actions?markup_string?trim?has_content>
        <footer class="onesa-actions">
            <div class="onesa-actions-in">${actions}</div>
        </footer>
    </#if>
</body>
</html>
</#macro>
