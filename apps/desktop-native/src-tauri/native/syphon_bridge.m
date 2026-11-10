// The Syphon output's Objective-C half (ADR-021): turns the receiver's
// NV12 frames into a BGRA Metal texture on the GPU (Core Image) and
// publishes it through Syphon, so OBS on the same Mac reads the cast
// without a window capture. Called from Rust (src/syphon.rs); every entry
// point takes one lock, so start/stop from the UI and publishing from the
// frame thread never interleave. No frame content is logged.

#import <AppKit/AppKit.h>
#import <CoreImage/CoreImage.h>
#import <CoreVideo/CoreVideo.h>
#import <Metal/Metal.h>
#import <Syphon/Syphon.h>

static NSLock *gLock;
static id<MTLDevice> gDevice;
static id<MTLCommandQueue> gQueue;
static CIContext *gContext;
static SyphonMetalServer *gServer;
/// The conversion target (GPU-only) and the still's upload texture.
static id<MTLTexture> gFrameTexture;
static id<MTLTexture> gStillTexture;

static void ensureLock(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{ gLock = [NSLock new]; });
}

static id<MTLTexture> texture(id<MTLTexture> current, size_t width, size_t height, MTLStorageMode storage, MTLTextureUsage usage) {
    if (current != nil && current.width == width && current.height == height) {
        return current;
    }
    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                         width:width
                                                                                        height:height
                                                                                     mipmapped:NO];
    descriptor.storageMode = storage;
    descriptor.usage = usage;
    return [gDevice newTextureWithDescriptor:descriptor];
}

/// Starts the server under `name` (idempotent). Syphon's discovery rides
/// the app's main run loop, which the Tauri shell keeps alive.
bool zf_syphon_start(const char *name) {
    ensureLock();
    [gLock lock];
    bool ok = true;
    if (gServer == nil) {
        gDevice = MTLCreateSystemDefaultDevice();
        if (gDevice == nil) {
            ok = false;
        } else {
            gQueue = [gDevice newCommandQueue];
            // No colour management: the YCbCr → RGB matrix only, so the
            // values OBS reads are the ones the stream carries.
            gContext = [CIContext contextWithMTLDevice:gDevice
                                               options:@{ kCIContextWorkingColorSpace : [NSNull null],
                                                          kCIContextCacheIntermediates : @NO }];
            gServer = [[SyphonMetalServer alloc] initWithName:[NSString stringWithUTF8String:name] device:gDevice options:nil];
            ok = gServer != nil;
        }
    }
    [gLock unlock];
    return ok;
}

void zf_syphon_stop(void) {
    ensureLock();
    [gLock lock];
    [gServer stop];
    gServer = nil;
    gFrameTexture = nil;
    gStillTexture = nil;
    gContext = nil;
    gQueue = nil;
    gDevice = nil;
    [gLock unlock];
}

bool zf_syphon_has_clients(void) {
    ensureLock();
    [gLock lock];
    bool clients = gServer != nil && gServer.hasClients;
    [gLock unlock];
    return clients;
}

/// Converts one NV12 pixel buffer on the GPU and publishes it. The buffer's
/// pixel format states its range (video / full), which Core Image honours.
bool zf_syphon_publish(CVPixelBufferRef pixelBuffer) {
    ensureLock();
    bool ok = false;
    @autoreleasepool {
        [gLock lock];
        if (gServer != nil && pixelBuffer != NULL) {
            const size_t width = CVPixelBufferGetWidth(pixelBuffer);
            const size_t height = CVPixelBufferGetHeight(pixelBuffer);
            gFrameTexture = texture(gFrameTexture, width, height, MTLStorageModePrivate,
                                    MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget);
            CIImage *image = [CIImage imageWithCVPixelBuffer:pixelBuffer options:@{ kCIImageColorSpace : [NSNull null] }];
            id<MTLCommandBuffer> commands = [gQueue commandBuffer];
            CIRenderDestination *destination = [[CIRenderDestination alloc] initWithMTLTexture:gFrameTexture commandBuffer:commands];
            // Syphon's surface is bottom row first (its OpenGL heritage; OBS
            // reads it that way — found live 2026-11-10, the picture was
            // upside down). Core Image's own origin is bottom-left, so an
            // unflipped render is already in that order and Syphon can blit.
            destination.flipped = NO;
            destination.colorSpace = nil;
            NSError *error = nil;
            if (gFrameTexture != nil && image != nil && [gContext startTaskToRender:image toDestination:destination error:&error] != nil) {
                [gServer publishFrameTexture:gFrameTexture
                             onCommandBuffer:commands
                                 imageRegion:NSMakeRect(0, 0, width, height)
                                     flipped:NO];
                ok = true;
            }
            [commands commit];
        }
        [gLock unlock];
    }
    return ok;
}

/// Publishes one dark still with `text` centred — what OBS shows between
/// casts instead of the last frame of the previous one.
bool zf_syphon_publish_still(size_t width, size_t height, const char *text) {
    ensureLock();
    bool ok = false;
    @autoreleasepool {
        [gLock lock];
        if (gServer != nil && width > 0 && height > 0) {
            const size_t rowBytes = width * 4;
            NSMutableData *pixels = [NSMutableData dataWithLength:rowBytes * height];
            CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
            CGContextRef context = CGBitmapContextCreate(pixels.mutableBytes, width, height, 8, rowBytes, space,
                                                         kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
            CGColorSpaceRelease(space);
            if (context != NULL) {
                CGContextSetRGBFillColor(context, 0.043, 0.055, 0.071, 1.0);
                CGContextFillRect(context, CGRectMake(0, 0, width, height));
                NSGraphicsContext *graphics = [NSGraphicsContext graphicsContextWithCGContext:context flipped:NO];
                [NSGraphicsContext saveGraphicsState];
                [NSGraphicsContext setCurrentContext:graphics];
                NSDictionary *attributes = @{
                    NSFontAttributeName : [NSFont systemFontOfSize:(CGFloat)height / 18.0 weight:NSFontWeightMedium],
                    NSForegroundColorAttributeName : [NSColor colorWithSRGBRed:0.54 green:0.58 blue:0.63 alpha:1.0],
                };
                NSString *label = [NSString stringWithUTF8String:text];
                NSSize size = [label sizeWithAttributes:attributes];
                [label drawAtPoint:NSMakePoint(((CGFloat)width - size.width) / 2.0, ((CGFloat)height - size.height) / 2.0)
                    withAttributes:attributes];
                [NSGraphicsContext restoreGraphicsState];
                CGContextRelease(context);

                gStillTexture = texture(gStillTexture, width, height, MTLStorageModeShared, MTLTextureUsageShaderRead);
                if (gStillTexture != nil) {
                    // The bitmap's first row in memory is the image's top;
                    // Syphon wants the bottom row first, so upload it reversed.
                    const uint8_t *rows = pixels.bytes;
                    for (size_t row = 0; row < height; row++) {
                        [gStillTexture replaceRegion:MTLRegionMake2D(0, row, width, 1)
                                         mipmapLevel:0
                                           withBytes:rows + (height - 1 - row) * rowBytes
                                         bytesPerRow:rowBytes];
                    }
                    id<MTLCommandBuffer> commands = [gQueue commandBuffer];
                    [gServer publishFrameTexture:gStillTexture
                                 onCommandBuffer:commands
                                     imageRegion:NSMakeRect(0, 0, width, height)
                                         flipped:NO];
                    [commands commit];
                    ok = true;
                }
            }
        }
        [gLock unlock];
    }
    return ok;
}

/// Copies the frame the server currently offers into `out` as BGRA rows in
/// the surface's own order (bottom row first), after the GPU has finished
/// everything queued. Returns
/// false when there is no frame or `capacity` is too small. Diagnostics and
/// tests only — never on the frame path.
bool zf_syphon_copy_frame(uint8_t *out, size_t capacity, size_t *width, size_t *height) {
    ensureLock();
    bool ok = false;
    @autoreleasepool {
        [gLock lock];
        if (gServer != nil) {
            id<MTLCommandBuffer> barrier = [gQueue commandBuffer];
            [barrier commit];
            [barrier waitUntilCompleted];
            id<MTLTexture> frame = [gServer newFrameImage];
            if (frame != nil && frame.width * frame.height * 4 <= capacity) {
                *width = frame.width;
                *height = frame.height;
                [frame getBytes:out
                    bytesPerRow:frame.width * 4
                     fromRegion:MTLRegionMake2D(0, 0, frame.width, frame.height)
                    mipmapLevel:0];
                ok = true;
            }
        }
        [gLock unlock];
    }
    return ok;
}
