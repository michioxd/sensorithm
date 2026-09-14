use serde::Deserialize;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;

pub const SENSOR_MASK: u8 = 0x3f;
const HANDSHAKE_TIMEOUT_SECONDS: u64 = 3;

#[derive(Debug, PartialEq, Eq)]
pub struct ClientMetadata {
    pub name: String,
    pub description: String,
}

#[derive(Deserialize)]
struct ClientInfo {
    name: String,
    os: String,
    app: String,
}

pub async fn read_client_metadata(
    reader: &mut BufReader<TcpStream>,
    fallback_description: &str,
) -> ClientMetadata {
    let mut line = String::new();
    let info = tokio::time::timeout(
        std::time::Duration::from_secs(HANDSHAKE_TIMEOUT_SECONDS),
        reader.read_line(&mut line),
    )
    .await
    .ok()
    .and_then(Result::ok)
    .filter(|bytes_read| *bytes_read > 0)
    .and_then(|_| parse_client_info(&line));

    info.unwrap_or_else(|| ClientMetadata {
        name: "Android Device".to_owned(),
        description: fallback_description.to_owned(),
    })
}

pub async fn write_server_metadata(reader: &mut BufReader<TcpStream>) {
    let info = format!(
        r#"{{"version":"{}","os":"{}"}}"#,
        env!("CARGO_PKG_VERSION"),
        std::env::consts::OS,
    );
    let _ = reader
        .get_mut()
        .write_all(format!("{info}\n").as_bytes())
        .await;
    let _ = reader.get_mut().flush().await;
}

fn parse_client_info(line: &str) -> Option<ClientMetadata> {
    serde_json::from_str::<ClientInfo>(line)
        .ok()
        .map(|info| ClientMetadata {
            name: info.name,
            description: format!("{} - v{}", info.os, info.app),
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_existing_android_handshake_shape() {
        let metadata =
            parse_client_info(r#"{"name":"Example Phone","os":"Android 14","app":"1.0"}"#).unwrap();
        assert_eq!(
            metadata,
            ClientMetadata {
                name: "Example Phone".to_owned(),
                description: "Android 14 - v1.0".to_owned(),
            }
        );
    }

    #[test]
    fn rejects_non_protocol_json() {
        assert_eq!(parse_client_info(r#"{"name":"incomplete"}"#), None);
    }
}
