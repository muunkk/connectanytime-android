#include "ocbridge.h"

#include <openconnect.h>
#include <pthread.h>
#include <errno.h>
#include <netdb.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

struct ocb_session {
    struct openconnect_info *vpn;
    int cmd_fd;
    char *user, *password, *second;
    char *server;
    ocb_log_fn log;
    ocb_event_fn event;
    void *ctx;
    ocb_trust_fn trust;
    ocb_protect_fn protect;
    bool answered_user, answered_password, answered_second;
    // Whether the login has started sending secrets, or been aborted for taking too long
    // before it did. Changed only by compare-and-swap from LOGIN_IDLE, so exactly one of
    // the two happens (see ocb_cancel_unless_answered).
    int login_state;
    char auth_error[512];
    char first_error[256]; // OpenConnect's first error in ocb_connect, to say why it failed
    char *connect_url;
    char *pin_name, *pin_ip; // resolve pin_name to pin_ip (see ocb_resume)
};

enum { LOGIN_IDLE, LOGIN_SENDING_SECRETS, LOGIN_ABORTED };

static void reconnected_cb(void *priv);

static void logf_(struct ocb_session *s, int level, const char *fmt, ...) {
    if (!s->log) return;
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    size_t n = strlen(buf);
    while (n && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = 0;
    if (n) s->log(s->ctx, level, buf);
}

// OpenConnect progress. Capped at PRG_INFO: DEBUG/TRACE dump HTTP bodies,
// which include the submitted password.
static void progress_cb(void *priv, int level, const char *fmt, ...) {
    struct ocb_session *s = priv;
    // OpenConnect has no reconnect callbacks, so watch its messages: our patch logs
    // each attempt; a dead peer or a failed attempt's sleep mark a dropped connection.
    // (Built without NLS, so the format strings are the English originals.)
    if (s->event && !strcmp(fmt, "Reconnecting to the server\n"))
        s->event(s->ctx, OCB_ATTEMPT, NULL);
    else if (s->event && (!strncmp(fmt, "CSTP Dead Peer Detection detected dead peer", 43) ||
                          !strncmp(fmt, "sleep %ds", 9)))
        s->event(s->ctx, OCB_RECONNECTING, NULL);
    // The server's limits on the session (how long it may last, sit idle, or wait for
    // the phone to come back), from the CONNECT reply's headers. Only these: other
    // DEBUG lines can hold the password or cookie.
    if (level == PRG_DEBUG && !strcmp(fmt, "%s: %s\n")) {
        static const char *limits[] = { "X-CSTP-Lease-Duration", "X-CSTP-Session-Timeout",
            "X-CSTP-Session-Timeout-Remaining", "X-CSTP-Idle-Timeout",
            "X-CSTP-Disconnected-Timeout", "X-CSTP-Session-Timeout-Alert-Interval", NULL };
        va_list ap;
        va_start(ap, fmt);
        const char *name = va_arg(ap, const char *), *value = va_arg(ap, const char *);
        va_end(ap);
        for (int i = 0; name && value && limits[i]; i++)
            if (!strcasecmp(name, limits[i])) logf_(s, PRG_INFO, "Server limit %s: %s", name + 7, value);
        return;
    }
    if (level > PRG_INFO) return;
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (level == PRG_ERR && !s->first_error[0]) {
        snprintf(s->first_error, sizeof s->first_error, "%s", buf);
        size_t n = strlen(s->first_error);
        while (n && (s->first_error[n - 1] == '\n' || s->first_error[n - 1] == '\r')) s->first_error[--n] = 0;
    }
    logf_(s, level, "%s", buf);
}

// Answers each login field at most once, so a wrong password fails instead of
// being retried (and can't lock the account).
static int auth_form_cb(void *priv, struct oc_auth_form *form) {
    struct ocb_session *s = priv;
    if (form->error) logf_(s, PRG_ERR, "Server: %s", form->error);

    for (struct oc_form_opt *opt = form->opts; opt; opt = opt->next) {
        if (opt->flags & OC_FORM_OPT_IGNORE) continue;
        if (opt->type == OC_FORM_OPT_HIDDEN || opt->type == OC_FORM_OPT_SELECT) continue; // keep server defaults

        const char *value = NULL;
        bool *answered = NULL;
        const char *what = opt->name ? opt->name : "?";
        if (opt->type == OC_FORM_OPT_TEXT && opt->name && !strcmp(opt->name, "username")) {
            value = s->user; answered = &s->answered_user;
        } else if (opt->type == OC_FORM_OPT_PASSWORD && opt->name &&
                   !strcmp(opt->name, "secondary_password")) {
            value = s->second; answered = &s->answered_second;
        } else if (opt->type == OC_FORM_OPT_PASSWORD && opt->name && !strcmp(opt->name, "password")) {
            value = s->password; answered = &s->answered_password;
        }

        if (!value) {
            snprintf(s->auth_error, sizeof s->auth_error, "The server asked for an unexpected field (%s).", what);
            return OC_FORM_RESULT_CANCELLED;
        }
        if (*answered) {
            snprintf(s->auth_error, sizeof s->auth_error,
                     form->error ? "Login failed: %s" : "Login failed. Check your NetID and password.",
                     form->error ? form->error : "");
            return OC_FORM_RESULT_CANCELLED;
        }
        if (value != s->user) {
            // About to send the password or the Duo answer: claim the login, unless the
            // deadline already aborted it, in which case send nothing.
            int idle = LOGIN_IDLE;
            if (!__atomic_compare_exchange_n(&s->login_state, &idle, LOGIN_SENDING_SECRETS, false,
                                             __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) &&
                idle != LOGIN_SENDING_SECRETS)
                return OC_FORM_RESULT_CANCELLED;
        }
        *answered = true;
        openconnect_set_option_value(opt, value);
        logf_(s, PRG_INFO, "Answered %s", what);
        if (value == s->second) logf_(s, PRG_INFO, "Waiting for Duo approval…");
    }
    return OC_FORM_RESULT_OK;
}

// OpenSSL has no Android trust store. Kotlin checks the DER chain using the
// platform X509TrustManager and verifies the original DNS name against SANs.
static int validate_cert_cb(void *priv, const char *reason) {
    struct ocb_session *s = priv;
    struct oc_cert *chain = NULL;
    int n = openconnect_get_peer_cert_chain(s->vpn, &chain);
    if (n <= 0 || !s->trust) return 1;
    const unsigned char **der = calloc(n, sizeof *der);
    int *lengths = calloc(n, sizeof *lengths);
    if (!der || !lengths) { free(der); free(lengths); openconnect_free_peer_cert_chain(s->vpn, chain); return 1; }
    for (int i = 0; i < n; i++) { der[i] = chain[i].der_data; lengths[i] = chain[i].der_len; }
    int ok = s->trust(s->ctx, der, lengths, n, openconnect_get_dnsname(s->vpn));
    free(der); free(lengths);
    openconnect_free_peer_cert_chain(s->vpn, chain);
    if (ok) logf_(s, PRG_INFO, "Server certificate verified by Android");
    else {
        logf_(s, PRG_ERR, "Server certificate rejected");
        snprintf(s->auth_error, sizeof s->auth_error, "The server's certificate isn't trusted.");
    }
    (void)reason;
    return ok ? 0 : 1;
}

static void protect_cb(void *priv, int fd) {
    struct ocb_session *s = priv;
    s->protect(s->ctx, fd);
}

static pthread_once_t ssl_once = PTHREAD_ONCE_INIT;
static void init_ssl(void) {
    // A reset socket must return EPIPE, not kill the VPN process.
    signal(SIGPIPE, SIG_IGN);
    openconnect_init_ssl();
}

ocb_session *ocb_new(const char *server, const char *user, const char *password,
                     const char *second_password, ocb_log_fn log,
                     ocb_event_fn event, ocb_trust_fn trust, ocb_protect_fn protect, void *ctx) {
    pthread_once(&ssl_once, init_ssl);

    struct ocb_session *s = calloc(1, sizeof *s);
    if (!s) return NULL;
    s->cmd_fd = -1;
    s->server = strdup(server);
    s->user = strdup(user);
    s->password = strdup(password);
    s->second = strdup(second_password);
    s->log = log;
    s->event = event;
    s->ctx = ctx;
    s->trust = trust;
    s->protect = protect;
    s->vpn = openconnect_vpninfo_new(NULL, validate_cert_cb, NULL, auth_form_cb, progress_cb, s);
    if (!s->vpn) { ocb_free(s); return NULL; }
    openconnect_set_protect_socket_handler(s->vpn, protect_cb);
    openconnect_set_loglevel(s->vpn, PRG_INFO);
    openconnect_set_reported_os(s->vpn, "android");
    openconnect_set_reconnected_handler(s->vpn, reconnected_cb);
    s->cmd_fd = openconnect_setup_cmd_pipe(s->vpn);
    return s;
}

int ocb_connect(ocb_session *s, char *err, size_t errlen) {
    if (openconnect_parse_url(s->vpn, s->server)) {
        snprintf(err, errlen, "Invalid server address.");
        return -1;
    }
    s->first_error[0] = 0;
    if (openconnect_obtain_cookie(s->vpn)) {
        if (s->auth_error[0]) {
            snprintf(err, errlen, "%s", s->auth_error);
            return -1;
        }
        // Nothing secret sent yet, so no Duo push and nothing to lock the account:
        // the network didn't get us to the login form. Safe to try again.
        int state = __atomic_load_n(&s->login_state, __ATOMIC_SEQ_CST);
        if (state == LOGIN_ABORTED) {
            snprintf(err, errlen, "Couldn't reach the VPN server on this network (no answer in 20 s).");
            return OCB_UNREACHABLE;
        }
        if (state == LOGIN_IDLE) {
            snprintf(err, errlen, "Couldn't reach the VPN server on this network%s%s%s",
                     s->first_error[0] ? " (" : "", s->first_error, s->first_error[0] ? ")." : ".");
            return OCB_UNREACHABLE;
        }
        snprintf(err, errlen, "Login failed or was cancelled%s%s%s",
                 s->first_error[0] ? " (" : "", s->first_error, s->first_error[0] ? ")." : ".");
        return -1;
    }
    if (openconnect_make_cstp_connection(s->vpn)) {
        snprintf(err, errlen, "Logged in, but couldn't open the VPN session.");
        return -1;
    }
    // As openconnect's own client does: otherwise every reconnect retries DTLS.
    if (openconnect_setup_dtls(s->vpn, 60)) {
        logf_(s, PRG_INFO, "DTLS unavailable; using TLS only");
        openconnect_disable_dtls(s->vpn);
    }
    return 0;
}

// Like openconnect's --resolve: send the server's name to the address the session
// was made on, so a round-robin DNS answer can't hand the cookie to another server.
// The normal answers follow, in case the pinned address can't be reached from this
// network (the list is tried in order, as openconnect's own --resolve merges them).
// The pin goes first only if this network's answers include it: one from another
// network, e.g. a NAT64 address from cellular, can hang on Wi-Fi past the watchdog.
static int pinned_getaddrinfo(void *priv, const char *node, const char *service,
                              const struct addrinfo *hints, struct addrinfo **res) {
    struct ocb_session *s = priv;
    if (!node || !s->pin_name || !s->pin_ip || strcasecmp(node, s->pin_name))
        return getaddrinfo(node, service, hints, res);
    struct addrinfo *pinned = NULL, *normal = NULL;
    int pinned_err = getaddrinfo(s->pin_ip, service, hints, &pinned);
    int normal_err = getaddrinfo(node, service, hints, &normal);
    if (pinned_err) { *res = normal; return normal_err; }
    bool usable = normal_err != 0;
    for (struct addrinfo *a = normal_err ? NULL : normal; a && !usable; a = a->ai_next)
        usable = a->ai_addrlen == pinned->ai_addrlen && !memcmp(a->ai_addr, pinned->ai_addr, a->ai_addrlen);
    if (!usable) { struct addrinfo *t = pinned; pinned = normal; normal = t; } // normal answers first
    struct addrinfo *tail = pinned;
    while (tail->ai_next) tail = tail->ai_next;
    tail->ai_next = normal_err ? NULL : normal;
    *res = pinned;
    return 0;
}

int ocb_resume(ocb_session *s, const char *url, const char *cookie, const char *dnsname,
               const char *ip, char *err, size_t errlen) {
    if (openconnect_parse_url(s->vpn, url) || openconnect_set_cookie(s->vpn, cookie)) {
        snprintf(err, errlen, "Invalid saved session.");
        return OCB_SESSION_ENDED;
    }
    if (dnsname && *dnsname && ip && *ip) {
        size_t n = strlen(ip);
        bool bracketed = n > 2 && ip[0] == '[' && ip[n - 1] == ']'; // IPv6 as in a URL
        s->pin_name = strdup(dnsname);
        s->pin_ip = bracketed ? strndup(ip + 1, n - 2) : strdup(ip);
        openconnect_override_getaddrinfo(s->vpn, pinned_getaddrinfo);
    }
    int ret = openconnect_make_cstp_connection(s->vpn);
    if (ret) {
        // The server answers 401 to a session it has ended (EPERM); anything else
        // is a failure to reach it.
        snprintf(err, errlen, ret == -EPERM ? "The VPN session has ended."
                                             : "Couldn't reach the VPN server.");
        return ret == -EPERM ? OCB_SESSION_ENDED : -1;
    }
    // As openconnect's own client does: otherwise every reconnect retries DTLS.
    if (openconnect_setup_dtls(s->vpn, 60)) {
        logf_(s, PRG_INFO, "DTLS unavailable; using TLS only");
        openconnect_disable_dtls(s->vpn);
    }
    return 0;
}

const char *ocb_session_url(ocb_session *s) {
    // openconnect_get_connect_url appends to its buffer on every call; ask once.
    if (!s->connect_url) {
        const char *url = openconnect_get_connect_url(s->vpn);
        s->connect_url = url ? strdup(url) : NULL;
    }
    return s->connect_url;
}

const char *ocb_session_cookie(ocb_session *s) {
    return openconnect_get_cookie(s->vpn);
}

const char *ocb_session_dnsname(ocb_session *s) {
    return openconnect_get_dnsname(s->vpn);
}

const char *ocb_session_ip(ocb_session *s) {
    return openconnect_get_hostname(s->vpn); // the address connected to, despite the name
}

static char *dup_or_null(const char *v) { return v && *v ? strdup(v) : NULL; }

static char *join_routes(struct oc_split_include *list) {
    size_t len = 0;
    for (struct oc_split_include *r = list; r; r = r->next) len += strlen(r->route) + 1;
    if (!len) return NULL;
    char *out = calloc(1, len + 1), *p = out;
    for (struct oc_split_include *r = list; r; r = r->next) p += sprintf(p, "%s\n", r->route);
    return out;
}

static char *config_key(const ocb_config *c);

int ocb_get_config(ocb_session *s, ocb_config *out) {
    const struct oc_ip_info *ip = NULL;
    memset(out, 0, sizeof *out);
    if (openconnect_get_ip_info(s->vpn, &ip, NULL, NULL) || !ip || !ip->addr) return -1;
    out->addr = dup_or_null(ip->addr);
    out->netmask = dup_or_null(ip->netmask ? ip->netmask : "255.255.255.255");
    out->addr6 = dup_or_null(ip->netmask6 ? ip->netmask6 : NULL);
    for (int i = 0; i < 3; i++) out->dns[i] = dup_or_null(ip->dns[i]);
    out->domain = dup_or_null(ip->domain);
    out->gateway = dup_or_null(ip->gateway_addr);
    out->split_includes = join_routes(ip->split_includes);
    out->split_excludes = join_routes(ip->split_excludes);
    out->split_dns = join_routes(ip->split_dns);
    out->mtu = ip->mtu;
    out->key = config_key(out);
    return 0;
}

/// Everything the tunnel settings are built from, to tell whether they changed.
static char *config_key(const ocb_config *c) {
    const char *parts[] = { c->addr, c->netmask, c->addr6, c->dns[0], c->dns[1], c->dns[2],
                            c->domain, c->gateway, c->split_includes, c->split_excludes, c->split_dns };
    size_t n = sizeof parts / sizeof *parts, len = 16;
    for (size_t i = 0; i < n; i++) len += (parts[i] ? strlen(parts[i]) : 0) + 1;
    char *key = calloc(1, len), *p = key;
    if (!key) return NULL;
    for (size_t i = 0; i < n; i++) p += sprintf(p, "%s|", parts[i] ? parts[i] : "");
    sprintf(p, "%d", c->mtu);
    return key;
}

// Runs on ocb_run's thread, after OpenConnect reconnected with the session cookie.
// Hands over the server's settings: it may have assigned a new address.
static void reconnected_cb(void *priv) {
    struct ocb_session *s = priv;
    logf_(s, PRG_INFO, "Reconnected with the existing session");
    ocb_config cfg;
    bool ok = ocb_get_config(s, &cfg) == 0 && cfg.key;
    if (s->event) s->event(s->ctx, OCB_RECONNECTED, ok ? &cfg : NULL);
    ocb_config_free(&cfg);
}

void ocb_config_free(ocb_config *c) {
    free(c->addr); free(c->netmask); free(c->addr6);
    for (int i = 0; i < 3; i++) free(c->dns[i]);
    free(c->domain); free(c->gateway);
    free(c->split_includes); free(c->split_excludes); free(c->split_dns);
    free(c->key);
    memset(c, 0, sizeof *c);
}

int ocb_run(ocb_session *s, int tun_fd, int reconnect_timeout) {
    // OpenConnect 9.21 tun.c only closes a tun when vpnc_script is set;
    // library.c vpninfo_free does not close an externally supplied fd. We never
    // set a script: ParcelFileDescriptor owns this borrowed fd across rejoins.
    if (openconnect_setup_tun_fd(s->vpn, tun_fd)) return -1;
    int ret;
    // Only a pause makes the mainloop return 0 (it closes its connections first).
    // Calling it again reconnects with the session cookie: no new login or Duo push.
    while ((ret = openconnect_mainloop(s->vpn, reconnect_timeout, RECONNECT_INTERVAL_MIN)) == 0) {}
    return ret;
}

void ocb_reconnect(ocb_session *s) {
    char cmd = OC_CMD_PAUSE;
    if (s && s->cmd_fd >= 0) (void)!write(s->cmd_fd, &cmd, 1);
}

void ocb_cancel(ocb_session *s) {
    char cmd = OC_CMD_CANCEL;
    if (s && s->cmd_fd >= 0) (void)!write(s->cmd_fd, &cmd, 1);
}

bool ocb_cancel_unless_answered(ocb_session *s) {
    if (!s) return false;
    int idle = LOGIN_IDLE;
    if (!__atomic_compare_exchange_n(&s->login_state, &idle, LOGIN_ABORTED, false,
                                     __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) &&
        idle != LOGIN_ABORTED)
        return false; // the password is on its way: let the login finish
    // Again on every call: OpenConnect only notices a cancel when it's waiting on the
    // socket, and after one it can go on to try the server's next address.
    ocb_cancel(s);
    return true;
}

void ocb_detach(ocb_session *s) {
    char cmd = OC_CMD_DETACH;
    if (s && s->cmd_fd >= 0) (void)!write(s->cmd_fd, &cmd, 1);
}

// Bionic at API 29 has no explicit_bzero (memset_explicit starts at API 34).
// A volatile wipe cannot be optimized away, unlike a plain memset before free.
static __attribute__((noinline)) void explicit_bzero(void *ptr, size_t len) {
    volatile unsigned char *p = ptr;
    while (len--) *p++ = 0;
}

static void wipe_free(char *p) {
    if (!p) return;
    explicit_bzero(p, strlen(p));
    free(p);
}

void ocb_free(ocb_session *s) {
    if (!s) return;
    if (s->vpn) openconnect_vpninfo_free(s->vpn);
    wipe_free(s->password);
    wipe_free(s->second);
    free(s->user);
    free(s->server);
    free(s->connect_url);
    free(s->pin_name);
    free(s->pin_ip);
    free(s);
}

