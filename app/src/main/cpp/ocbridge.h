// Thin C layer over libopenconnect for the packet tunnel extension.
// Swift can't implement OpenConnect's printf-style callbacks, so they live here.
#ifndef OCBRIDGE_H
#define OCBRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
void ocb_set_network(uint64_t handle);

typedef struct ocb_session ocb_session;
typedef int (*ocb_trust_fn)(void *ctx, const unsigned char **der, const int *lengths, int count, const char *hostname);
typedef void (*ocb_protect_fn)(void *ctx, int fd);
typedef void (*ocb_log_fn)(void *ctx, int level, const char *line);
/// On OpenConnect's thread, after ocb_switch_tun: the tun fd to switch to, or -1.
typedef int (*ocb_next_tun_fn)(void *ctx);

/// Tunnel settings pushed by the server. Strings are owned by the struct.
typedef struct {
    char *addr, *netmask;     // IPv4
    char *addr6;              // IPv6 "addr/len", may be NULL
    char *dns[3];
    char *domain;
    char *gateway;            // server IP actually connected to
    char *split_includes;     // newline-separated "net/mask", may be NULL
    char *split_excludes;
    char *split_dns;
    int mtu;
    char *key;                // all of the above in one string, to tell configs apart
} ocb_config;

/// OCB_RECONNECTING: the connection dropped and OpenConnect is retrying.
/// OCB_ATTEMPT: a reconnect attempt is starting.
/// OCB_RECONNECTED: back up; `cfg` holds the server's (possibly new) tunnel settings.
/// OCB_TUN_SWITCHED: OpenConnect now uses the fd from ocb_next_tun_fn and no longer
/// touches the previous one, which may be closed.
/// `cfg` is NULL for the other events, and only valid during the call.
enum { OCB_RECONNECTING, OCB_ATTEMPT, OCB_RECONNECTED, OCB_TUN_SWITCHED };
typedef void (*ocb_event_fn)(void *ctx, int event, const ocb_config *cfg);

ocb_session *ocb_new(const char *server, const char *user, const char *password,
                     const char *second_password, ocb_log_fn log,
                     ocb_event_fn event, ocb_trust_fn trust, ocb_protect_fn protect,
                     ocb_next_tun_fn next_tun, void *ctx);

/// Logs in (username, password, then second password for Duo) and opens the
/// VPN session. Blocks until Duo is approved or rejected. 0 on success,
/// OCB_UNREACHABLE if it failed before sending the password (so no Duo push was
/// sent and trying again is safe), any other negative value on other failures.
#define OCB_UNREACHABLE (-3)
int ocb_connect(ocb_session *s, char *err, size_t errlen);

/// Rejoins the session from an earlier login instead of logging in: no password,
/// no Duo. The arguments come from the ocb_session_* getters below; `dnsname` and
/// `ip` (may be NULL) pin the server's name to the address the session was made
/// on. 0 on success, OCB_SESSION_ENDED if the server no longer accepts the session,
/// any other negative value on other failures (e.g. no network).
#define OCB_SESSION_ENDED (-2)
int ocb_resume(ocb_session *s, const char *url, const char *cookie, const char *dnsname,
               const char *ip, char *err, size_t errlen);

/// What ocb_resume needs to rejoin the session later: url, cookie, dnsname, ip, and a
/// candidate cookie ("" if none). The cookie carries the STRAP key the server last
/// accepted. The candidate carries a newer key that a CONNECT presented without an
/// answer: the server may hold it, so try it only if the cookie gets OCB_SESSION_ENDED.
/// As of the last connect, resume or reconnect, or the end of ocb_run. Thread-safe:
/// copies taken while ocb_run may be reconnecting. Free them with ocb_saved_free.
/// 0 on success. Both cookies are credentials; never log them.
#define OCB_SAVED_COUNT 5
int ocb_session_saved(ocb_session *s, char *out[OCB_SAVED_COUNT]);
void ocb_saved_free(char *saved[OCB_SAVED_COUNT]);

int ocb_get_config(ocb_session *s, ocb_config *out);
void ocb_config_free(ocb_config *c);

/// Runs the tunnel on `tun_fd` until cancelled or the server ends it. If the
/// connection drops, keeps retrying with the existing session (no new login or
/// Duo) for up to `reconnect_timeout` seconds of sleeps between attempts. Reports
/// OCB_RECONNECTING when it notices a dropped connection and OCB_RECONNECTED after.
int ocb_run(ocb_session *s, int tun_fd, int reconnect_timeout);

/// Thread-safe: drops the current connection and reconnects right away with
/// the existing session, e.g. after switching networks.
void ocb_reconnect(ocb_session *s);

/// Thread-safe: asks ocb_run to switch to a new tun fd on its own thread, keeping
/// the connection. It asks ocb_next_tun_fn for the fd, then reports OCB_TUN_SWITCHED.
void ocb_switch_tun(ocb_session *s);

/// Thread-safe: aborts ocb_connect or ocb_run, logging out of the server's session.
void ocb_cancel(ocb_session *s);

/// Thread-safe: aborts ocb_connect if it hasn't started sending the password yet, e.g.
/// because it's stuck reaching the server; it then returns OCB_UNREACHABLE and sends
/// no password or Duo answer. A login that has started sending it (and so may send a Duo push) is left
/// to finish. Returns whether it aborted. Call again until ocb_connect returns: a
/// single cancel may only end the attempt on one of the server's addresses.
bool ocb_cancel_unless_answered(ocb_session *s);

/// Thread-safe: like ocb_cancel, but leaves the server's session open, so
/// ocb_resume can rejoin it.
void ocb_detach(ocb_session *s);

void ocb_free(ocb_session *s);

#endif
