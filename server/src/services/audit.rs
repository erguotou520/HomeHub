//! WireGuard identity audit.
//!
//! The server has no user system: whoever reaches the service through the
//! WireGuard tunnel is trusted. Every data-plane request is attributed to a
//! peer (by source IP) and written to `audit_logs` asynchronously.

use std::collections::HashMap;
use std::net::IpAddr;

use anyhow::Result;
use sqlx::Row;
use tokio::sync::mpsc;

use crate::config::{AuditConfig, Config};
use crate::db::Db;
use crate::models::trash::{AuditLog, PeerTraffic, WgPeer};

#[derive(Debug, Clone)]
pub struct AuditEvent {
    pub ip: Option<String>,
    pub method: String,
    pub path: String,
    pub status: u16,
    pub bytes: u64,
}

/// Asynchronous, batched audit writer.
#[derive(Clone)]
pub struct AuditWriter {
    tx: mpsc::Sender<AuditEvent>,
}

impl AuditWriter {
    pub fn record(&self, event: AuditEvent) {
        // Never block a request on the audit pipeline.
        let _ = self.tx.try_send(event);
    }
}

/// Start the background flusher. Returns the writer handle.
pub fn spawn(db: Db, config: &Config) -> AuditWriter {
    let (tx, mut rx) = mpsc::channel::<AuditEvent>(4096);
    let cfg: AuditConfig = config.runtime.audit.clone();
    let db2 = db.clone();

    tokio::spawn(async move {
        let mut buffer: Vec<AuditEvent> = Vec::with_capacity(cfg.batch_size.max(64));
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(
            cfg.flush_interval_secs.max(1),
        ));
        loop {
            tokio::select! {
                maybe = rx.recv() => match maybe {
                    Some(event) => {
                        buffer.push(event);
                        if buffer.len() >= cfg.batch_size {
                            flush(&db2, &mut buffer).await;
                        }
                    }
                    None => break,
                },
                _ = interval.tick() => {
                    if !buffer.is_empty() {
                        flush(&db2, &mut buffer).await;
                    }
                }
            }
        }
        if !buffer.is_empty() {
            flush(&db2, &mut buffer).await;
        }
    });

    AuditWriter { tx }
}

async fn flush(db: &Db, buffer: &mut Vec<AuditEvent>) {
    let events = std::mem::take(buffer);
    if events.is_empty() {
        return;
    }
    let now = crate::db::now();
    let mut peers: HashMap<Option<String>, Option<i64>> = HashMap::new();
    for event in &events {
        if !peers.contains_key(&event.ip) {
            let peer = match &event.ip {
                Some(ip) => resolve_peer(db, ip).await.ok().flatten().map(|p| p.id),
                None => None,
            };
            peers.insert(event.ip.clone(), peer);
        }
    }
    if let Err(e) = (|| async {
        let mut tx = db.begin().await?;
        for event in &events {
            let peer_id = peers.get(&event.ip).copied().flatten();
            sqlx::query(
                "INSERT INTO audit_logs (peer_id, peer_ip, method, path, status, bytes, created_at) \
                 VALUES (?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(peer_id)
            .bind(&event.ip)
            .bind(&event.method)
            .bind(&event.path)
            .bind(event.status as i64)
            .bind(event.bytes as i64)
            .bind(now)
            .execute(&mut *tx)
            .await?;
        }
        tx.commit().await?;
        Ok::<(), anyhow::Error>(())
    })()
    .await
    {
        tracing::warn!("audit flush failed: {}", e);
    }
}

// ──────────────────────────────── peer registry ────────────────────────────

pub async fn resolve_peer(db: &Db, ip: &str) -> Result<Option<WgPeer>> {
    // Exact tunnel IP first, then the /24 network it belongs to.
    let peer = sqlx::query_as::<_, WgPeer>(
        "SELECT id, public_key, name, tunnel_ip, first_seen, last_seen, enabled, source \
         FROM wg_peers WHERE tunnel_ip = ?",
    )
    .bind(ip)
    .fetch_optional(db)
    .await?;
    if peer.is_some() {
        return Ok(peer);
    }
    let prefix = match ip.rsplit_once('.') {
        Some((p, _)) => format!("{}.%", p),
        None => return Ok(None),
    };
    Ok(
        sqlx::query_as::<_, WgPeer>(
            "SELECT id, public_key, name, tunnel_ip, first_seen, last_seen, enabled, source \
             FROM wg_peers WHERE tunnel_ip LIKE ? LIMIT 1",
        )
        .bind(prefix)
        .fetch_optional(db)
        .await?,
    )
}

/// Insert or refresh a peer, returning its id.
pub async fn upsert_peer(
    db: &Db,
    public_key: Option<&str>,
    name: &str,
    tunnel_ip: &str,
    source: &str,
) -> Result<i64> {
    let now = crate::db::now();
    if let Some(key) = public_key {
        let existing: Option<(i64,)> =
            sqlx::query_as("SELECT id FROM wg_peers WHERE public_key = ?")
                .bind(key)
                .fetch_optional(db)
                .await?;
        if let Some((id,)) = existing {
            sqlx::query("UPDATE wg_peers SET name = ?, tunnel_ip = ?, last_seen = ?, source = ? WHERE id = ?")
                .bind(name)
                .bind(tunnel_ip)
                .bind(now)
                .bind(source)
                .bind(id)
                .execute(db)
                .await?;
            return Ok(id);
        }
    }

    let existing: Option<(i64,)> = sqlx::query_as("SELECT id FROM wg_peers WHERE tunnel_ip = ?")
        .bind(tunnel_ip)
        .fetch_optional(db)
        .await?;
    if let Some((id,)) = existing {
        sqlx::query("UPDATE wg_peers SET name = ?, last_seen = ?, source = ? WHERE id = ?")
            .bind(name)
            .bind(now)
            .bind(source)
            .bind(id)
            .execute(db)
            .await?;
        return Ok(id);
    }

    let id: i64 = sqlx::query_scalar(
        "INSERT INTO wg_peers (public_key, name, tunnel_ip, first_seen, last_seen, enabled, source) \
         VALUES (?, ?, ?, ?, ?, 1, ?) RETURNING id",
    )
    .bind(public_key)
    .bind(name)
    .bind(tunnel_ip)
    .bind(now)
    .bind(now)
    .bind(source)
    .fetch_one(db)
    .await?;
    Ok(id)
}

pub async fn list_peers(db: &Db) -> Result<Vec<WgPeer>> {
    Ok(sqlx::query_as::<_, WgPeer>(
        "SELECT id, public_key, name, tunnel_ip, first_seen, last_seen, enabled, source \
         FROM wg_peers ORDER BY name",
    )
    .fetch_all(db)
    .await?)
}

pub async fn set_peer_name(db: &Db, id: i64, name: &str) -> Result<()> {
    sqlx::query("UPDATE wg_peers SET name = ? WHERE id = ?")
        .bind(name)
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn set_peer_enabled(db: &Db, id: i64, enabled: bool) -> Result<()> {
    sqlx::query("UPDATE wg_peers SET enabled = ? WHERE id = ?")
        .bind(if enabled { 1 } else { 0 })
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// Read `wg show <iface> dump` (only works when co-located with the gateway).
pub async fn discover_peers(interface: &str) -> Result<Vec<crate::models::peer::DiscoveredPeer>> {
    let output = tokio::process::Command::new("wg")
        .arg("show")
        .arg(interface)
        .arg("dump")
        .output()
        .await?;
    if !output.status.success() {
        anyhow::bail!(
            "`wg show {} dump` failed: {}",
            interface,
            String::from_utf8_lossy(&output.stderr)
        );
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let mut peers = Vec::new();
    for line in text.lines().skip(1) {
        let cols: Vec<&str> = line.split('\t').collect();
        if cols.len() < 4 {
            continue;
        }
        let public_key = cols[0].to_string();
        let tunnel_ip = cols[3]
            .split('/')
            .next()
            .unwrap_or(cols[3])
            .split(',')
            .next()
            .unwrap_or("")
            .to_string();
        if tunnel_ip.is_empty() {
            continue;
        }
        let handshake: i64 = cols.get(4).and_then(|v| v.parse().ok()).unwrap_or(0);
        peers.push(crate::models::peer::DiscoveredPeer {
            public_key,
            tunnel_ip,
            name: None,
            last_handshake: if handshake > 0 { Some(handshake) } else { None },
        });
    }
    Ok(peers)
}

/// Merge discovered peers (or manual config) into the registry.
pub async fn sync_peers(db: &Db, config: &Config) -> Result<usize> {
    let mut n = 0;
    for declared in &config.wireguard.peers {
        if declared.tunnel_ip.is_empty() {
            continue;
        }
        upsert_peer(
            db,
            declared.public_key.as_deref(),
            &declared.name,
            &declared.tunnel_ip,
            "manual",
        )
        .await?;
        n += 1;
    }
    if config.wireguard.auto_sync {
        match discover_peers(&config.wireguard.interface).await {
            Ok(peers) => {
                for peer in peers {
                    let name = format!("wg-{}", &peer.tunnel_ip);
                    upsert_peer(db, Some(&peer.public_key), &name, &peer.tunnel_ip, "wg-show")
                        .await?;
                    n += 1;
                }
            }
            Err(e) => tracing::debug!("wireguard auto-sync unavailable: {}", e),
        }
    }
    Ok(n)
}

// ─────────────────────────────────── queries ───────────────────────────────

pub async fn logs(db: &Db, peer_id: Option<i64>, limit: i64, offset: i64) -> Result<Vec<AuditLog>> {
    let (sql, bind_peer) = match peer_id {
        Some(_) => (
            "SELECT id, peer_id, peer_ip, method, path, status, bytes, created_at \
             FROM audit_logs WHERE peer_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            true,
        ),
        None => (
            "SELECT id, peer_id, peer_ip, method, path, status, bytes, created_at \
             FROM audit_logs ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            false,
        ),
    };
    let mut q = sqlx::query_as::<_, AuditLog>(sql);
    if bind_peer {
        q = q.bind(peer_id);
    }
    Ok(q.bind(limit).bind(offset).fetch_all(db).await?)
}

/// Requests and bytes per peer.
pub async fn traffic(db: &Db) -> Result<Vec<PeerTraffic>> {
    let rows = sqlx::query(
        "SELECT a.peer_id AS peer_id, \
                COALESCE(p.name, 'unknown') AS peer_name, \
                COALESCE(p.tunnel_ip, a.peer_ip, '-') AS tunnel_ip, \
                COUNT(*) AS requests, \
                COALESCE(SUM(a.bytes), 0) AS bytes, \
                MAX(a.created_at) AS last_seen \
         FROM audit_logs a LEFT JOIN wg_peers p ON p.id = a.peer_id \
         GROUP BY a.peer_id, peer_name, tunnel_ip \
         ORDER BY bytes DESC",
    )
    .fetch_all(db)
    .await?;
    let mut out = Vec::new();
    for row in rows {
        out.push(PeerTraffic {
            peer_id: row.try_get("peer_id").unwrap_or(0),
            peer_name: row.try_get("peer_name").unwrap_or_default(),
            tunnel_ip: row.try_get("tunnel_ip").unwrap_or_default(),
            requests: row.try_get("requests").unwrap_or(0),
            bytes: row.try_get("bytes").unwrap_or(0),
            last_seen: row.try_get("last_seen").ok().flatten(),
        });
    }
    Ok(out)
}

/// Drop audit rows older than the retention period.
pub async fn purge_expired(db: &Db, retention_days: u32) -> Result<u64> {
    let cutoff = crate::db::now() - retention_days as i64 * 86400;
    let res = sqlx::query("DELETE FROM audit_logs WHERE created_at < ?")
        .bind(cutoff)
        .execute(db)
        .await?;
    Ok(res.rows_affected())
}

/// Best effort parse of a socket address into an IP string.
pub fn ip_of(addr: &std::net::SocketAddr) -> String {
    match addr.ip() {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => v6.to_string(),
    }
}
