//! Minimal ONVIF PTZ client (raw SOAP over HTTP + WS-Security PasswordDigest).
//!
//! Why this lives on the server: the phone has no camera credentials (the
//! camera list is credential-masked) and is usually not even on the camera's
//! network, so pan/tilt/zoom has to be executed by the box that can actually
//! reach the device. The phone just POSTs an intent.
//!
//! Two details were measured against the reference device
//! (TP-Link TL-IPC44AW-COLOR 6.0, ONVIF 2.20, single entry
//! `http://<ip>:2020/onvif/service`) and both are easy to get wrong:
//!
//! 1. **The digest is over the raw nonce bytes**, not over the base64 text of
//!    the nonce. Hashing the base64 string (a very common copy-paste) earns
//!    `ter:NotAuthorized` on this device.
//! 2. **Namespace prefixes inside `<s:Body>` must be declared on the
//!    envelope.** Sending `<td:GetDeviceInformation/>` without
//!    `xmlns:td=…` is invalid XML and the device answers
//!    `ter:ActionNotSupported` — which looks like "the camera has no ONVIF"
//!    even though auth never even ran.
//!
//! `GotoHomePosition` is *not* implemented: the device rejects it with
//! `ter:ActionNotSupported` (verified), so there is no "return to centre".

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use base64_lite::b64;
use sha1_lite::sha1;

const NS_TD: &str = "http://www.onvif.org/ver10/device/wsdl";
const NS_TRT: &str = "http://www.onvif.org/ver10/media/wsdl";
const NS_TPTZ: &str = "http://www.onvif.org/ver10/ptz/wsdl";
const NS_TT: &str = "http://www.onvif.org/ver10/schema";

/// The only Password `Type` this device accepts. The
/// `…wssecurity-utility-1.0#PasswordDigest` spelling that shows up in a lot of
/// sample code is silently treated as an unknown auth mode → `NotAuthorized`.
const PW_TYPE: &str =
    "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest";
const NONCE_ENCODING: &str =
    "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary";

/// How long a resolved profile token stays valid.
const TOKEN_TTL: Duration = Duration::from_secs(600);

#[derive(Debug)]
pub struct OnvifError(pub String);

impl std::fmt::Display for OnvifError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}
impl std::error::Error for OnvifError {}

type Result<T> = std::result::Result<T, OnvifError>;

/// A resolved camera endpoint: where to send SOAP, with what credentials.
#[derive(Debug, Clone)]
pub struct Endpoint {
    pub service_url: String,
    pub user: String,
    pub password: String,
    pub host: String,
}

/// Parse `rtsp://user:pass@host[:port]/path` into an ONVIF endpoint.
///
/// The ONVIF single-entry port is 2020 on the reference device; the RTSP port
/// (554) is irrelevant here.
pub fn endpoint_from_rtsp(rtsp_url: &str) -> Result<Endpoint> {
    let rest = rtsp_url
        .strip_prefix("rtsp://")
        .ok_or_else(|| OnvifError(format!("not an rtsp url: {rtsp_url}")))?;
    // authority ends at the first '/' — everything after is the path.
    let authority = rest.split('/').next().unwrap_or(rest);
    let (creds, hostport) = match authority.rsplit_once('@') {
        Some((c, h)) => (Some(c), h),
        None => (None, authority),
    };
    let (user, password) = match creds {
        Some(c) => {
            let (u, p) = c.split_once(':').unwrap_or((c, ""));
            (u.to_string(), p.to_string())
        }
        None => (String::new(), String::new()),
    };
    // Strip the port; ONVIF lives on its own port.
    let host = hostport.split(':').next().unwrap_or(hostport).to_string();
    if host.is_empty() {
        return Err(OnvifError("rtsp url has no host".into()));
    }
    Ok(Endpoint {
        service_url: format!("http://{host}:2020/onvif/service"),
        user,
        password,
        host,
    })
}

/// Cache of resolved media-profile tokens (one per camera name).
#[derive(Default)]
pub struct TokenCache {
    inner: Mutex<HashMap<String, (String, Instant)>>,
}

impl TokenCache {
    pub fn get(&self, cam: &str) -> Option<String> {
        let g = self.inner.lock().ok()?;
        let (tok, at) = g.get(cam)?;
        if at.elapsed() < TOKEN_TTL {
            Some(tok.clone())
        } else {
            None
        }
    }

    pub fn put(&self, cam: &str, token: &str) {
        if let Ok(mut g) = self.inner.lock() {
            g.insert(cam.to_string(), (token.to_string(), Instant::now()));
        }
    }
}

// ────────────────────────────────── SOAP ──────────────────────────────────

fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

/// ISO-8601 UTC without sub-second precision, e.g. `2026-10-09T08:34:47Z`.
fn created_now() -> String {
    chrono::Utc::now().format("%Y-%m-%dT%H:%M:%SZ").to_string()
}

/// PasswordDigest = base64(SHA1(raw_nonce ‖ created ‖ password)).
fn digest(nonce_raw: &[u8], created: &str, password: &str) -> String {
    let mut buf = Vec::with_capacity(nonce_raw.len() + created.len() + password.len());
    buf.extend_from_slice(nonce_raw);
    buf.extend_from_slice(created.as_bytes());
    buf.extend_from_slice(password.as_bytes());
    b64(&sha1(&buf))
}

fn envelope(ep: &Endpoint, action_uri: &str, body: &str) -> (String, String) {
    // `rand` is already a dependency; 16 bytes is the customary nonce size.
    let mut nonce_raw = [0u8; 16];
    {
        use rand::RngCore;
        rand::thread_rng().fill_bytes(&mut nonce_raw);
    }
    let nonce_b64 = b64(&nonce_raw);
    let created = created_now();
    let pw = digest(&nonce_raw, &created, &ep.password);
    let msg_id = format!("{{{}}}", uuid_like());
    let env = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
 xmlns:wsa5="http://schemas.xmlsoap.org/ws/2005/08/addressing"
 xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
 xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
 xmlns:td="{NS_TD}" xmlns:trt="{NS_TRT}" xmlns:tptz="{NS_TPTZ}" xmlns:tt="{NS_TT}">
  <s:Header>
    <wsa5:Action s:mustUnderstand="true">{action_uri}</wsa5:Action>
    <wsa5:MessageID>{msg_id}</wsa5:MessageID>
    <wsa5:To s:mustUnderstand="true">{to}</wsa5:To>
    <wsa5:ReplyTo><wsa5:Address>http://www.w3.org/2005/08/addressing/role/anonymous</wsa5:Address></wsa5:ReplyTo>
    <wsse:Security><wsse:UsernameToken>
      <wsse:Username>{user}</wsse:Username>
      <wsse:Password Type="{PW_TYPE}">{pw}</wsse:Password>
      <wsu:Created>{created}</wsu:Created>
      <wsu:Nonce EncodingType="{NONCE_ENCODING}">{nonce_b64}</wsu:Nonce>
    </wsse:UsernameToken></wsse:Security>
  </s:Header>
  <s:Body>{body}</s:Body>
</s:Envelope>"#,
        to = ep.service_url,
        user = xml_escape(&ep.user),
    );
    (env, action_uri.to_string())
}

/// Enough of a UUIDv4 for a MessageID (uniqueness, not crypto).
fn uuid_like() -> String {
    use rand::RngCore;
    let mut b = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut b);
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-4{:01x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        b[0], b[1], b[2], b[3], b[4], b[5], b[6] & 0x0f, b[7],
        (b[8] & 0x3f) | 0x80, b[9], b[10], b[11], b[12], b[13], b[14], b[15]
    )
}

/// Text between the first `start` marker and the next `end` marker.
fn between<'a>(raw: &'a str, start: &str, end: &str) -> &'a str {
    let Some(i) = raw.find(start) else {
        return "";
    };
    let from = i + start.len();
    match raw[from..].find(end) {
        Some(j) => raw[from..from + j].trim(),
        None => raw[from..].trim(),
    }
}

/// First `token="…"` appearing after `marker`.
fn attr_after(raw: &str, marker: &str) -> Option<String> {
    let i = raw.find(marker)?;
    let rest = &raw[i..];
    let j = rest.find("token=\"")?;
    let after = &rest[j + "token=\"".len()..];
    let k = after.find('"')?;
    Some(after[..k].to_string())
}

fn fault_of(text: &str) -> Option<String> {
    if !text.contains("Fault") {
        return None;
    }
    // `<SOAP-ENV:Reason><SOAP-ENV:Text xml:lang="en">msg</SOAP-ENV:Text>` is the
    // human-readable one; `<SOAP-ENV:Subcode><SOAP-ENV:Value>ter:NotAuthorized`
    // is what actually tells you *why*. This device ships the Subcode only, so
    // take that when the Text is absent.
    let reason = between(text, "<SOAP-ENV:Text", "</");
    let reason = reason.split('>').next_back().unwrap_or("").trim();
    let subcode = between(text, "<SOAP-ENV:Subcode>", "</SOAP-ENV:Subcode>");
    let subcode = between(subcode, "<SOAP-ENV:Value>", "</");
    let code = between(text, "<SOAP-ENV:Value>", "</");
    let msg = if !reason.is_empty() {
        reason
    } else if !subcode.is_empty() {
        subcode
    } else {
        code
    };
    Some(if msg.is_empty() {
        "ONVIF fault".to_string()
    } else {
        msg.to_string()
    })
}

async fn soap(client: &reqwest::Client, ep: &Endpoint, service: &str, action: &str, body: &str) -> Result<String> {
    let action_uri = format!("{service}/{action}");
    let (env, action_uri) = envelope(ep, &action_uri, body);
    let resp = client
        .post(&ep.service_url)
        .header(reqwest::header::CONTENT_TYPE, "application/soap+xml; charset=utf-8")
        .header("SOAPAction", &action_uri)
        .body(env)
        .send()
        .await
        .map_err(|e| OnvifError(format!("ONVIF {} → {e}", ep.host)))?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(OnvifError(format!(
            "ONVIF {} {}: {}",
            action,
            status.as_u16(),
            fault_of(&text).unwrap_or_else(|| "no fault detail".into())
        )));
    }
    if let Some(f) = fault_of(&text) {
        return Err(OnvifError(format!("ONVIF {action}: {f}")));
    }
    Ok(text)
}

fn http() -> reqwest::Client {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build()
        .unwrap_or_default()
}

/// Resolve the media profile token used by the PTZ actions (the first
/// profile; the reference device exposes `profile_1` = main, `profile_2` =
/// sub, and PTZ works with either).
pub async fn profile_token(ep: &Endpoint) -> Result<String> {
    let client = http();
    let xml = soap(&client, ep, NS_TRT, "GetProfiles", "<trt:GetProfiles/>").await?;
    attr_after(&xml, ":Profiles").ok_or_else(|| OnvifError("GetProfiles: no profile token".into()))
}

/// Continuous pan/tilt/zoom. Callers must follow up with [`stop`] (the UI
/// stops on finger-up) — the device keeps moving otherwise.
pub async fn continuous_move(ep: &Endpoint, token: &str, x: f64, y: f64, z: f64) -> Result<()> {
    let client = http();
    let body = format!(
        "<tptz:ContinuousMove>\
           <tptz:ProfileToken>{token}</tptz:ProfileToken>\
           <tptz:Velocity>\
             <tt:PanTilt x=\"{x:.3}\" y=\"{y:.3}\"/>\
             <tt:Zoom x=\"{z:.3}\"/>\
           </tptz:Velocity>\
         </tptz:ContinuousMove>",
        x = x.clamp(-1.0, 1.0),
        y = y.clamp(-1.0, 1.0),
        z = z.clamp(-1.0, 1.0),
    );
    soap(&client, ep, NS_TPTZ, "ContinuousMove", &body).await?;
    Ok(())
}

pub async fn stop(ep: &Endpoint, token: &str) -> Result<()> {
    let client = http();
    let body = format!(
        "<tptz:Stop><tptz:ProfileToken>{token}</tptz:ProfileToken>\
         <tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>"
    );
    soap(&client, ep, NS_TPTZ, "Stop", &body).await?;
    Ok(())
}

// ─────────────────────── tiny SHA1 / base64 (no new deps) ─────────────────

mod sha1_lite {
    /// SHA-1 (RFC 3174). ONVIF's PasswordDigest is defined over SHA-1, and the
    /// crate isn't in the dependency tree; 60 lines beats a new lockfile entry
    /// that the image build would have to fetch.
    pub fn sha1(data: &[u8]) -> [u8; 20] {
        let mut h: [u32; 5] = [0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0];
        let ml = (data.len() as u64) * 8;
        let mut msg = data.to_vec();
        msg.push(0x80);
        while msg.len() % 64 != 56 {
            msg.push(0);
        }
        msg.extend_from_slice(&ml.to_be_bytes());

        let mut w = [0u32; 80];
        for chunk in msg.chunks(64) {
            for i in 0..16 {
                w[i] = u32::from_be_bytes([
                    chunk[i * 4],
                    chunk[i * 4 + 1],
                    chunk[i * 4 + 2],
                    chunk[i * 4 + 3],
                ]);
            }
            for i in 16..80 {
                w[i] = (w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]).rotate_left(1);
            }
            let (mut a, mut b, mut c, mut d, mut e) = (h[0], h[1], h[2], h[3], h[4]);
            for (i, wi) in w.iter().enumerate() {
                let (f, k) = match i {
                    0..=19 => ((b & c) | ((!b) & d), 0x5A827999u32),
                    20..=39 => (b ^ c ^ d, 0x6ED9EBA1),
                    40..=59 => ((b & c) | (b & d) | (c & d), 0x8F1BBCDC),
                    _ => (b ^ c ^ d, 0xCA62C1D6),
                };
                let tmp = a
                    .rotate_left(5)
                    .wrapping_add(f)
                    .wrapping_add(e)
                    .wrapping_add(k)
                    .wrapping_add(*wi);
                e = d;
                d = c;
                c = b.rotate_left(30);
                b = a;
                a = tmp;
            }
            h[0] = h[0].wrapping_add(a);
            h[1] = h[1].wrapping_add(b);
            h[2] = h[2].wrapping_add(c);
            h[3] = h[3].wrapping_add(d);
            h[4] = h[4].wrapping_add(e);
        }
        let mut out = [0u8; 20];
        for (i, v) in h.iter().enumerate() {
            out[i * 4..i * 4 + 4].copy_from_slice(&v.to_be_bytes());
        }
        out
    }
}

mod base64_lite {
    const TBL: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    pub fn b64(data: &[u8]) -> String {
        let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
        for chunk in data.chunks(3) {
            let b = [
                chunk[0],
                *chunk.get(1).unwrap_or(&0),
                *chunk.get(2).unwrap_or(&0),
            ];
            let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
            out.push(TBL[(n >> 18) as usize & 63] as char);
            out.push(TBL[(n >> 12) as usize & 63] as char);
            out.push(if chunk.len() > 1 {
                TBL[(n >> 6) as usize & 63] as char
            } else {
                '='
            });
            out.push(if chunk.len() > 2 {
                TBL[n as usize & 63] as char
            } else {
                '='
            });
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha1_matches_known_vectors() {
        // RFC 3174 / FIPS 180 test vectors.
        assert_eq!(
            sha1_lite::sha1(b"abc").to_vec(),
            hex::decode("a9993e364706816aba3e25717850c26c9cd0d89d").unwrap()
        );
        assert_eq!(
            sha1_lite::sha1(b"").to_vec(),
            hex::decode("da39a3ee5e6b4b0d3255bfef95601890afd80709").unwrap()
        );
        assert_eq!(
            sha1_lite::sha1(b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq").to_vec(),
            hex::decode("84983e441c3bd26ebaae4aa1f95129e5e54670f1").unwrap()
        );
    }

    #[test]
    fn base64_matches_known_vectors() {
        assert_eq!(base64_lite::b64(b""), "");
        assert_eq!(base64_lite::b64(b"f"), "Zg==");
        assert_eq!(base64_lite::b64(b"fo"), "Zm8=");
        assert_eq!(base64_lite::b64(b"foo"), "Zm9v");
        assert_eq!(base64_lite::b64(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn endpoint_parses_credentials_and_host() {
        let ep = endpoint_from_rtsp("rtsp://admin:P46WeYWdcc@192.168.8.144/stream1").unwrap();
        assert_eq!(ep.host, "192.168.8.144");
        assert_eq!(ep.service_url, "http://192.168.8.144:2020/onvif/service");
        assert_eq!(ep.user, "admin");
        assert_eq!(ep.password, "P46WeYWdcc");
    }

    #[test]
    fn endpoint_parses_explicit_rtsp_port() {
        let ep = endpoint_from_rtsp("rtsp://u:p@10.0.0.5:554/stream2").unwrap();
        assert_eq!(ep.host, "10.0.0.5");
        assert_eq!(ep.service_url, "http://10.0.0.5:2020/onvif/service");
    }

    #[test]
    fn endpoint_rejects_non_rtsp() {
        assert!(endpoint_from_rtsp("http://x/y").is_err());
    }

    #[test]
    fn attr_after_finds_first_profile_token() {
        let xml = r#"<trt:Profiles token="profile_1" fixed="true"><tt:Name>mainStream</tt:Name></trt:Profiles><trt:Profiles token="profile_2"/>"#;
        assert_eq!(attr_after(xml, ":Profiles").unwrap(), "profile_1");
    }

    #[test]
    fn fault_of_extracts_subcode_value() {
        let xml = r#"<SOAP-ENV:Body><SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Sender</SOAP-ENV:Value><SOAP-ENV:Subcode><SOAP-ENV:Value>ter:NotAuthorized</SOAP-ENV:Value></SOAP-ENV:Subcode></SOAP-ENV:Code></SOAP-ENV:Fault></SOAP-ENV:Body>"#;
        // The subcode is the actionable part; the top-level Value is just
        // "Sender".
        assert_eq!(fault_of(xml).unwrap(), "ter:NotAuthorized");
    }

    #[test]
    fn fault_of_prefers_reason_text() {
        let xml = r#"<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Sender</SOAP-ENV:Value></SOAP-ENV:Code><SOAP-ENV:Reason><SOAP-ENV:Text xml:lang="en">no such profile</SOAP-ENV:Text></SOAP-ENV:Reason></SOAP-ENV:Fault>"#;
        assert_eq!(fault_of(xml).unwrap(), "no such profile");
    }

    #[test]
    fn fault_of_is_none_for_a_normal_envelope() {
        assert!(fault_of("<s:Envelope><s:Body><trt:GetProfilesResponse/></s:Body></s:Envelope>").is_none());
    }

    #[test]
    fn envelope_declares_every_prefix_used() {
        let ep = Endpoint {
            service_url: "http://1.2.3.4:2020/onvif/service".into(),
            user: "admin".into(),
            password: "pw".into(),
            host: "1.2.3.4".into(),
        };
        let (env, _) = envelope(&ep, &format!("{NS_TPTZ}/Stop"), "<tptz:Stop/>");
        // The body uses `tptz:`/`tt:`; the envelope must declare them or the
        // device parses an empty body and answers ActionNotSupported.
        for prefix in ["xmlns:td=", "xmlns:trt=", "xmlns:tptz=", "xmlns:tt="] {
            assert!(env.contains(prefix), "missing {prefix}");
        }
        assert!(env.contains("<wsse:UsernameToken>"));
        assert!(env.contains(PW_TYPE));
    }

    #[test]
    fn envelope_digest_is_over_raw_nonce() {
        let nonce = [1u8, 2, 3, 4];
        let created = "2026-10-09T08:34:47Z";
        // Recompute the expected value independently.
        let mut buf = Vec::new();
        buf.extend_from_slice(&nonce);
        buf.extend_from_slice(created.as_bytes());
        buf.extend_from_slice(b"pw");
        assert_eq!(
            digest(&nonce, created, "pw"),
            base64_lite::b64(&sha1_lite::sha1(&buf))
        );
        // The classic mistake — hashing base64(nonce) instead of the raw
        // bytes — must NOT be what we send: this device answers
        // `ter:NotAuthorized` for it. Guard the fix.
        let wrong = format!("{}{}pw", base64_lite::b64(&nonce), created);
        assert_ne!(
            digest(&nonce, created, "pw"),
            base64_lite::b64(&sha1_lite::sha1(wrong.as_bytes()))
        );
    }
}
