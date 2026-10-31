// syphon-helper — the macOS half of caster's virtual camera (ADR-007).
//
// A tiny native bridge: the Electron main process pipes raw frames over stdin,
// this helper uploads them to a Metal texture and publishes via the Syphon
// framework (BSD-3) so OBS/VJ apps on the same Mac can pick the feed up as a
// source. No Apple entitlement, no system extension.
//
// Wire format on stdin (little-endian), repeated per frame:
//   uint32 magic 'ZFRV' (0x5A465256) | uint32 width | uint32 height
//   width * height * 4 bytes, BGRA, top-down rows, tightly packed
// EOF or a desynced magic ends the helper (exit 0 / exit 1).
//
// Build: see build.sh in this directory (compiles the Syphon framework from
// source + this file, universal arm64+x86_64).

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <Syphon/Syphon.h>

#include <stdint.h>
#include <string.h>

static const uint32_t FRAME_MAGIC = 0x5A465256; // "ZFRV"

static bool readExact(uint8_t *buffer, size_t bytes) {
    size_t done = 0;
    while (done < bytes) {
        size_t got = fread(buffer + done, 1, bytes - done, stdin);
        if (got == 0) return false; // EOF (the parent closed the pipe)
        done += got;
    }
    return true;
}

int main(int argc, char *argv[]) {
    @autoreleasepool {
        const BOOL flipped = getenv("SYPHON_FLIPPED") != NULL;

        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        id<MTLCommandQueue> queue = nil;
        SyphonMetalServer *server = nil;
        __block id<MTLTexture> texture = nil;
        __block uint32_t texWidth = 0, texHeight = 0;
        __block uint8_t *frame = NULL;
        __block size_t frameCapacity = 0;

        if (device == nil) {
            fprintf(stderr, "syphon-helper: no Metal device\n");
            return 1;
        }
        queue = [device newCommandQueue];
        server = [[SyphonMetalServer alloc] initWithName:@"Zero-Friction Local Cast" device:device options:nil];
        if (server == nil) {
            fprintf(stderr, "syphon-helper: Syphon server failed to start\n");
            return 1;
        }
        fprintf(stderr, "syphon-helper: publishing (flipped=%d)\n", flipped);

        // The frame loop runs off the main thread: Syphon's discovery rides
        // NSDistributedNotificationCenter, which needs a live main runloop to
        // receive SyphonServerAnnounceRequest from clients (OBS, inspectors)
        // and answer — a blocking read loop would leave the server invisible.
        __block volatile BOOL publishOk = YES;
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
            @autoreleasepool {
                for (;;) {
                    uint32_t header[3]; // magic, width, height
                    if (!readExact((uint8_t *)header, sizeof(header))) break;
                    if (header[0] != FRAME_MAGIC) {
                        fprintf(stderr, "syphon-helper: frame desync — stopping\n");
                        publishOk = NO;
                        break;
                    }
                    const uint32_t width = header[1], height = header[2];
                    if (width == 0 || height == 0 || width > 8192 || height > 8192) {
                        fprintf(stderr, "syphon-helper: unreasonable dims %ux%u\n", width, height);
                        publishOk = NO;
                        break;
                    }
                    const size_t bytes = (size_t)width * height * 4;
                    if (bytes > frameCapacity) {
                        free(frame);
                        frame = malloc(bytes);
                        frameCapacity = bytes;
                        if (frame == NULL) { publishOk = NO; break; }
                    }
                    if (!readExact(frame, bytes)) break;

                    // (Re)create the texture when the dims changed (shouldn't happen —
                    // the camera's dims are fixed at creation; handled for safety).
                    if (texture == nil || width != texWidth || height != texHeight) {
                        MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:width height:height mipmapped:NO];
                        descriptor.storageMode = MTLStorageModeShared;
                        descriptor.usage = MTLTextureUsageShaderRead;
                        texture = [device newTextureWithDescriptor:descriptor];
                        texWidth = width;
                        texHeight = height;
                    }

                    MTLRegion region = MTLRegionMake2D(0, 0, width, height);
                    [texture replaceRegion:region mipmapLevel:0 withBytes:frame bytesPerRow:(NSUInteger)width * 4];

                    @autoreleasepool {
                        id<MTLCommandBuffer> commands = [queue commandBuffer];
                        [server publishFrameTexture:texture
                                      onCommandBuffer:commands
                                         imageRegion:NSMakeRect(0, 0, width, height)
                                             flipped:flipped];
                        [commands commit];
                    }
                }
            }
            // The pipe ended (parent teardown) — stop and let the process exit.
            [server stop];
            fprintf(stderr, "syphon-helper: stdin closed — exiting\n");
            exit(publishOk ? 0 : 1);
        });

        // Main thread: keep the runloop alive for Syphon discovery traffic.
        [[NSRunLoop mainRunLoop] run];
        return 0;
    }
}
