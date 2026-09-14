use base64::{Engine as _, engine::general_purpose::STANDARD};
pub struct DecodedPreview {
    pub rgba: Vec<u8>,
    pub width: u32,
    pub height: u32,
}

pub fn decode_jpeg(
    image_base64: &str,
    expected_width: u32,
    expected_height: u32,
) -> Result<DecodedPreview, String> {
    if expected_width == 0
        || expected_height == 0
        || expected_width > MAX_PREVIEW_DIMENSION
        || expected_height > MAX_PREVIEW_DIMENSION
    {
        return Err(format!(
            "Unsupported preview dimensions: {expected_width}x{expected_height}",
        ));
    }
    let bytes = STANDARD
        .decode(image_base64)
        .map_err(|error| format!("Invalid preview encoding: {error}"))?;
    let decoded = image::load_from_memory_with_format(&bytes, image::ImageFormat::Jpeg)
        .map_err(|error| format!("Invalid preview image: {error}"))?
        .to_rgba8();
    let (width, height) = decoded.dimensions();
    if width != expected_width || height != expected_height {
        return Err(format!(
            "Preview dimensions do not match: received {width}x{height}, expected {expected_width}x{expected_height}",
        ));
    }
    Ok(DecodedPreview {
        rgba: decoded.into_raw(),
        width,
        height,
    })
}

const MAX_PREVIEW_DIMENSION: u32 = 1024;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_invalid_base64() {
        assert!(decode_jpeg("not base64", 1, 1).is_err());
    }

    #[test]
    fn rejects_unbounded_dimensions_before_decoding() {
        assert!(decode_jpeg("", MAX_PREVIEW_DIMENSION + 1, 1).is_err());
    }
}
