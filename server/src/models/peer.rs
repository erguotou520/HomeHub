//! WireGuard peer registry models.

use serde::Serialize;

/// A peer as parsed from `wg show <iface> dump`.
#[derive(Debug, Clone, Serialize)]
pub struct DiscoveredPeer {
    pub public_key: String,
    pub tunnel_ip: String,
    pub name: Option<String>,
    pub last_handshake: Option<i64>,
}
