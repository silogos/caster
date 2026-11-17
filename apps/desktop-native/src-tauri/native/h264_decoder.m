// The USB cast's H.264 decoder (ADR-034): VideoToolbox, hardware, one
// access unit in, one NV12 IOSurface-backed CVPixelBuffer out, synchronously.
// Over Wi-Fi libwebrtc decodes; over the cable there is no WebRTC, so the
// receiver decodes the phone's MediaCodec stream itself. Called from Rust
// (src/h264.rs) on the USB link's video thread only. No frame content is
// logged.

#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <VideoToolbox/VideoToolbox.h>
#include <stdlib.h>
#include <string.h>

typedef struct ZfH264Decoder {
    VTDecompressionSessionRef session;
    CMVideoFormatDescriptionRef format;
    CVPixelBufferRef last; // set by the output callback, taken by zf_h264_decode
    OSStatus lastStatus;
} ZfH264Decoder;

static void onDecoded(void *refcon, void *frameRefcon, OSStatus status, VTDecodeInfoFlags flags,
                      CVImageBufferRef image, CMTime pts, CMTime duration) {
    (void)frameRefcon; (void)flags; (void)pts; (void)duration;
    ZfH264Decoder *d = (ZfH264Decoder *)refcon;
    d->lastStatus = status;
    if (status == noErr && image != NULL) {
        if (d->last) CVPixelBufferRelease(d->last);
        d->last = CVPixelBufferRetain(image);
    }
}

/// Start of the next Annex-B start code at or after `from`, and its length (3 or 4).
static size_t nextStartCode(const uint8_t *p, size_t len, size_t from, size_t *codeLen) {
    for (size_t i = from; i + 3 <= len; i++) {
        if (p[i] == 0 && p[i + 1] == 0) {
            if (p[i + 2] == 1) { *codeLen = 3; return i; }
            if (i + 4 <= len && p[i + 2] == 0 && p[i + 3] == 1) { *codeLen = 4; return i; }
        }
    }
    *codeLen = 0;
    return len;
}

/// Calls `each` for every NAL unit of an Annex-B buffer (without its start code).
static void forEachNal(const uint8_t *p, size_t len, void (^each)(const uint8_t *nal, size_t nalLen)) {
    size_t code = 0;
    size_t start = nextStartCode(p, len, 0, &code);
    while (start < len) {
        size_t body = start + code;
        size_t nextCode = 0;
        size_t next = nextStartCode(p, len, body, &nextCode);
        if (next > body) each(p + body, next - body);
        start = next;
        code = nextCode;
    }
}

/// Reads SPS (type 7) and PPS (type 8) out of a codec-config buffer. 1 when both were found.
int zf_h264_parameter_sets(const uint8_t *config, size_t len, const uint8_t **sps, size_t *spsLen,
                           const uint8_t **pps, size_t *ppsLen) {
    __block const uint8_t *s = NULL, *q = NULL;
    __block size_t sl = 0, ql = 0;
    forEachNal(config, len, ^(const uint8_t *nal, size_t nalLen) {
        int type = nal[0] & 0x1F;
        if (type == 7 && s == NULL) { s = nal; sl = nalLen; }
        if (type == 8 && q == NULL) { q = nal; ql = nalLen; }
    });
    *sps = s; *spsLen = sl; *pps = q; *ppsLen = ql;
    return s != NULL && q != NULL;
}

ZfH264Decoder *zf_h264_create(const uint8_t *config, size_t configLen, int32_t *width, int32_t *height,
                              OSStatus *status) {
    const uint8_t *sps, *pps;
    size_t spsLen, ppsLen;
    if (!zf_h264_parameter_sets(config, configLen, &sps, &spsLen, &pps, &ppsLen)) {
        *status = kCMFormatDescriptionError_InvalidParameter;
        return NULL;
    }
    const uint8_t *sets[2] = {sps, pps};
    const size_t sizes[2] = {spsLen, ppsLen};
    CMVideoFormatDescriptionRef format = NULL;
    *status = CMVideoFormatDescriptionCreateFromH264ParameterSets(kCFAllocatorDefault, 2, sets, sizes, 4, &format);
    if (*status != noErr) return NULL;
    CMVideoDimensions dims = CMVideoFormatDescriptionGetDimensions(format);
    *width = dims.width;
    *height = dims.height;

    ZfH264Decoder *d = calloc(1, sizeof(ZfH264Decoder));
    d->format = format;
    // NV12 video range (what Android's encoders emit), on IOSurfaces Metal
    // can read: the display layer and Syphon take the buffer as it is.
    NSDictionary *attrs = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
        (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
        (id)kCVPixelBufferMetalCompatibilityKey: @YES,
    };
    NSDictionary *spec = @{(id)kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: @YES};
    VTDecompressionOutputCallbackRecord callback = {onDecoded, d};
    *status = VTDecompressionSessionCreate(kCFAllocatorDefault, format, (__bridge CFDictionaryRef)spec,
                                           (__bridge CFDictionaryRef)attrs, &callback, &d->session);
    if (*status != noErr) {
        CFRelease(format);
        free(d);
        return NULL;
    }
    VTSessionSetProperty(d->session, kVTDecompressionPropertyKey_RealTime, kCFBooleanTrue);
    return d;
}

/// Decodes one Annex-B access unit. Returns a +1 pixel buffer, or NULL (status says why;
/// noErr with NULL = the decoder produced no picture for this unit).
CVPixelBufferRef zf_h264_decode(ZfH264Decoder *d, const uint8_t *au, size_t len, int64_t ptsUs, OSStatus *status) {
    // Annex-B → AVCC: every slice NAL gets a 4-byte big-endian length; the
    // parameter sets and delimiters are already in the format description.
    uint8_t *avcc = malloc(len + 64);
    __block size_t out = 0;
    __block size_t cap = len + 64;
    __block uint8_t *buf = avcc;
    forEachNal(au, len, ^(const uint8_t *nal, size_t nalLen) {
        int type = nal[0] & 0x1F;
        if (type == 7 || type == 8 || type == 9) return;
        if (out + 4 + nalLen > cap) {
            cap = (out + 4 + nalLen) * 2;
            buf = realloc(buf, cap);
        }
        buf[out++] = (uint8_t)(nalLen >> 24);
        buf[out++] = (uint8_t)(nalLen >> 16);
        buf[out++] = (uint8_t)(nalLen >> 8);
        buf[out++] = (uint8_t)nalLen;
        memcpy(buf + out, nal, nalLen);
        out += nalLen;
    });
    if (out == 0) {
        free(buf);
        *status = noErr;
        return NULL;
    }
    CMBlockBufferRef block = NULL;
    // kCFAllocatorMalloc: the block buffer frees `buf` when it is released.
    *status = CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, buf, out, kCFAllocatorMalloc, NULL, 0, out, 0,
                                                 &block);
    if (*status != noErr) {
        free(buf);
        return NULL;
    }
    CMSampleTimingInfo timing = {kCMTimeInvalid, CMTimeMake(ptsUs, 1000000), kCMTimeInvalid};
    CMSampleBufferRef sample = NULL;
    size_t sampleSize = out;
    *status = CMSampleBufferCreateReady(kCFAllocatorDefault, block, d->format, 1, 1, &timing, 1, &sampleSize, &sample);
    CFRelease(block);
    if (*status != noErr) return NULL;
    d->lastStatus = noErr;
    // Flags 0: synchronous — the callback has run when this returns.
    *status = VTDecompressionSessionDecodeFrame(d->session, sample, 0, NULL, NULL);
    CFRelease(sample);
    if (*status == noErr) *status = d->lastStatus;
    CVPixelBufferRef picture = d->last;
    d->last = NULL;
    return picture;
}

void zf_h264_destroy(ZfH264Decoder *d) {
    if (d == NULL) return;
    if (d->session) {
        VTDecompressionSessionInvalidate(d->session);
        CFRelease(d->session);
    }
    if (d->format) CFRelease(d->format);
    if (d->last) CVPixelBufferRelease(d->last);
    free(d);
}
