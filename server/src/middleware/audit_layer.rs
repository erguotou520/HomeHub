//! Audit middleware: attribute every data-plane request to a WireGuard peer.

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, Response};
use futures_util::future::BoxFuture;
use std::convert::Infallible;
use std::net::SocketAddr;
use std::task::{Context, Poll};
use tower::{Layer, Service};

use crate::services::audit::{AuditEvent, AuditWriter};

#[derive(Clone)]
pub struct AuditLayer {
    writer: AuditWriter,
}

impl AuditLayer {
    pub fn new(writer: AuditWriter) -> Self {
        Self { writer }
    }
}

impl<S> Layer<S> for AuditLayer {
    type Service = AuditMiddleware<S>;

    fn layer(&self, inner: S) -> Self::Service {
        AuditMiddleware {
            inner,
            writer: self.writer.clone(),
        }
    }
}

#[derive(Clone)]
pub struct AuditMiddleware<S> {
    inner: S,
    writer: AuditWriter,
}

impl<S> Service<Request<Body>> for AuditMiddleware<S>
where
    S: Service<Request<Body>, Response = Response<Body>, Error = Infallible> + Clone + Send + 'static,
    S::Future: Send + 'static,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = BoxFuture<'static, Result<Self::Response, Self::Error>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request<Body>) -> Self::Future {
        let mut inner = self.inner.clone();
        let writer = self.writer.clone();

        let ip = req
            .extensions()
            .get::<ConnectInfo<SocketAddr>>()
            .map(|ci| crate::services::audit::ip_of(&ci.0));
        let method = req.method().to_string();
        let path = req.uri().path().to_string();

        Box::pin(async move {
            // Body consumption is not measured; we record the response size
            // from the Content-Length header, which covers media streaming.
            let res = inner.call(req).await?;
            let status = res.status().as_u16();
            let bytes = res
                .headers()
                .get(axum::http::header::CONTENT_LENGTH)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(0);
            writer.record(AuditEvent {
                ip,
                method,
                path,
                status,
                bytes,
            });
            Ok(res)
        })
    }
}
