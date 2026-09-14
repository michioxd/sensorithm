use serde::{Deserialize, Serialize};
use tokio::io::{
    AsyncBufRead, AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt,
};

pub const SENSOR_MASK: u8 = 0x3f;
pub const CONTROL_FRAME_PREFIX: u8 = 0xff;
pub const MAX_CONTROL_FRAME_BYTES: usize = 2 * 1024 * 1024;
const HANDSHAKE_TIMEOUT_SECONDS: u64 = 3;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct SyncedSettings {
    pub size_x: i32,
    pub size_y: i32,
    pub spacing: i32,
    pub angle: i32,
    pub exposure: i32,
    pub threshold: i32,
    pub offset_x: f32,
    pub offset_y: f32,
}

impl SyncedSettings {
    pub fn is_valid(&self) -> bool {
        (0..=100).contains(&self.size_x)
            && (0..=50).contains(&self.size_y)
            && (0..=100).contains(&self.spacing)
            && (0..=360).contains(&self.angle)
            && (0..=100).contains(&self.exposure)
            && (0..=255).contains(&self.threshold)
            && (0.0..=1.0).contains(&self.offset_x)
            && (0.0..=1.0).contains(&self.offset_y)
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMessage {
    Recalibrate,
    Settings { settings: SyncedSettings },
    SetTorch { enabled: bool },
    RestartCamera,
    RequestPreview { request_id: String },
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientMessage {
    Settings {
        settings: SyncedSettings,
    },
    TorchState {
        available: bool,
        enabled: bool,
    },
    Telemetry {
        battery_percent: u8,
        temperature_celsius: Option<f32>,
        #[serde(default)]
        charging: bool,
    },
    Preview {
        request_id: String,
        width: u32,
        height: u32,
        image_base64: String,
    },
    Error {
        operation: String,
        request_id: Option<String>,
        message: String,
    },
}

#[derive(Debug, PartialEq, Eq)]
pub struct ClientMetadata {
    pub name: String,
    pub description: String,
}

#[derive(Debug, PartialEq)]
pub enum ClientFrame {
    SensorMask(u8),
    Control(ClientMessage),
}

#[derive(Deserialize)]
struct ClientInfo {
    name: String,
    os: String,
    app: String,
}

#[derive(Serialize)]
struct ServerHello<'a> {
    version: &'a str,
    os: &'a str,
    accepted: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    message: Option<&'a str>,
}

pub async fn read_client_metadata<R>(reader: &mut R, fallback_description: &str) -> ClientMetadata
where
    R: AsyncBufRead + Unpin,
{
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

pub async fn write_server_hello<W>(
    stream: &mut W,
    accepted: bool,
    message: Option<&str>,
) -> std::io::Result<()>
where
    W: AsyncWrite + Unpin,
{
    let hello = ServerHello {
        version: env!("CARGO_PKG_VERSION"),
        os: std::env::consts::OS,
        accepted,
        message,
    };
    let json = serde_json::to_string(&hello).expect("server hello is serializable");
    stream.write_all(json.as_bytes()).await?;
    stream.write_all(b"\n").await?;
    stream.flush().await
}

pub async fn write_server_message<W>(stream: &mut W, message: &ServerMessage) -> std::io::Result<()>
where
    W: AsyncWrite + Unpin,
{
    let json = serde_json::to_string(message)
        .map_err(|error| std::io::Error::new(std::io::ErrorKind::InvalidData, error))?;
    stream.write_all(json.as_bytes()).await?;
    stream.write_all(b"\n").await?;
    stream.flush().await
}

pub async fn read_client_frame<R>(reader: &mut R) -> std::io::Result<ClientFrame>
where
    R: AsyncRead + Unpin,
{
    let prefix = reader.read_u8().await?;
    if prefix != CONTROL_FRAME_PREFIX {
        // Preserve the original protocol behavior: every non-control byte is a
        // real-time sensor update and only the lower six bits are meaningful.
        return Ok(ClientFrame::SensorMask(prefix & SENSOR_MASK));
    }

    let length = reader.read_u32().await? as usize;
    if length == 0 || length > MAX_CONTROL_FRAME_BYTES {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("invalid control frame length {length}"),
        ));
    }
    let mut payload = vec![0; length];
    reader.read_exact(&mut payload).await?;
    let message = serde_json::from_slice(&payload)
        .map_err(|error| std::io::Error::new(std::io::ErrorKind::InvalidData, error))?;
    Ok(ClientFrame::Control(message))
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
    fn settings_validation_rejects_out_of_range_values() {
        let valid = SyncedSettings {
            size_x: 15,
            size_y: 5,
            spacing: 10,
            angle: 180,
            exposure: 10,
            threshold: 30,
            offset_x: 0.5,
            offset_y: 0.5,
        };
        assert!(valid.is_valid());
        assert!(
            !SyncedSettings {
                threshold: 256,
                ..valid.clone()
            }
            .is_valid()
        );
        assert!(
            !SyncedSettings {
                offset_x: f32::NAN,
                ..valid
            }
            .is_valid()
        );
    }

    #[test]
    fn control_messages_have_a_tagged_extensible_shape() {
        let json = serde_json::to_string(&ServerMessage::SetTorch { enabled: true }).unwrap();
        assert_eq!(json, r#"{"type":"set_torch","enabled":true}"#);
        assert_eq!(
            serde_json::from_str::<ServerMessage>(&json).unwrap(),
            ServerMessage::SetTorch { enabled: true }
        );
    }

    #[tokio::test]
    async fn legacy_sensor_bytes_keep_only_the_six_sensor_bits() {
        let mut input = &b"W"[..];
        assert_eq!(
            read_client_frame(&mut input).await.unwrap(),
            ClientFrame::SensorMask(0x57 & SENSOR_MASK),
        );
    }

    #[test]
    fn telemetry_uses_a_small_structured_control_message() {
        let message = ClientMessage::Telemetry {
            battery_percent: 82,
            temperature_celsius: Some(34.6),
            charging: true,
        };
        let json = serde_json::to_string(&message).unwrap();
        assert_eq!(
            json,
            r#"{"type":"telemetry","battery_percent":82,"temperature_celsius":34.6,"charging":true}"#,
        );
        assert_eq!(
            serde_json::from_str::<ClientMessage>(&json).unwrap(),
            message
        );
    }
}
