// The OBS Virtual Camera output's Objective-C half (ADR-039): feeds the
// receiver's NV12 frames into the sink stream of OBS Studio's own CMIO
// camera extension, so every camera app on the Mac sees the cast as
// "OBS Virtual Camera" — with OBS itself closed.
//
// Everything here is public CoreMediaIO API; what is OBS's own is only the
// device's UID, read from OBS's installed extension bundle at run time
// (never hard-coded), and the fact that its extension passes on whatever
// sample buffers its sink is given. Called from Rust (src/obs_camera.rs);
// every entry point takes one lock. No frame content is logged.

#import <AppKit/AppKit.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreMediaIO/CMIOHardware.h>
#import <CoreVideo/CoreVideo.h>

/// OBS Studio's bundle identifier.
static NSString *const kObsBundleId = @"com.obsproject.obs-studio";
/// The key in the extension's Info.plist that holds the camera's UID.
static NSString *const kObsDeviceUidKey = @"OBSCameraDeviceUUID";

/// zf_obscam_probe's answers. Keep in step with `Availability` in obs_camera.rs.
enum {
    ZF_OBSCAM_READY = 0,
    ZF_OBSCAM_NO_OBS = 1,       // OBS Studio is not installed
    ZF_OBSCAM_NO_EXTENSION = 2, // OBS is too old for the camera extension (before 30), or it moved the UID
    ZF_OBSCAM_NOT_SET_UP = 3,   // the extension is not allowed in macOS yet: no such camera
};

/// zf_obscam_send's answers. Keep in step with obs_camera.rs.
enum {
    ZF_OBSCAM_SENT = 0,
    ZF_OBSCAM_QUEUE_FULL = 1,
    ZF_OBSCAM_FAILED = 2,
};

static NSLock *gLock;
static CMIOObjectID gDevice;
static CMIOStreamID gSink;
static CMSimpleQueueRef gQueue;
static bool gRunning;
static CMVideoFormatDescriptionRef gFormat;

static void ensureLock(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{ gLock = [NSLock new]; });
}

static CMIOObjectPropertyAddress address(CMIOObjectPropertySelector selector) {
    return (CMIOObjectPropertyAddress){ selector, kCMIOObjectPropertyScopeGlobal, kCMIOObjectPropertyElementMain };
}

/// The UID OBS's camera extension declares, from the app at `appPath`.
static NSString *deviceUidInApp(NSString *appPath) {
    NSString *dir = [appPath stringByAppendingPathComponent:@"Contents/Library/SystemExtensions"];
    for (NSString *item in [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil]) {
        if (![item hasSuffix:@".systemextension"]) continue;
        NSString *plist = [dir stringByAppendingPathComponent:[item stringByAppendingPathComponent:@"Contents/Info.plist"]];
        id uid = [NSDictionary dictionaryWithContentsOfFile:plist][kObsDeviceUidKey];
        if ([uid isKindOfClass:[NSString class]] && [[NSUUID alloc] initWithUUIDString:uid] != nil) return uid;
    }
    return nil;
}

/// For the tests: writes the UID found in the app at `appPath` into `out`.
bool zf_obscam_uid_in_app(const char *appPath, char *out, size_t capacity) {
    NSString *uid = deviceUidInApp([NSString stringWithUTF8String:appPath]);
    return uid != nil && [uid getCString:out maxLength:capacity encoding:NSUTF8StringEncoding];
}

static CMIOObjectID findDevice(NSString *uid) {
    CMIOObjectPropertyAddress devices = address(kCMIOHardwarePropertyDevices);
    UInt32 size = 0, used = 0;
    if (CMIOObjectGetPropertyDataSize(kCMIOObjectSystemObject, &devices, 0, NULL, &size) != noErr || size == 0) return 0;
    NSMutableData *ids = [NSMutableData dataWithLength:size];
    if (CMIOObjectGetPropertyData(kCMIOObjectSystemObject, &devices, 0, NULL, size, &used, ids.mutableBytes) != noErr) return 0;
    const CMIOObjectID *list = ids.bytes;
    for (size_t i = 0; i < used / sizeof(CMIOObjectID); i++) {
        CMIOObjectPropertyAddress uidAddress = address(kCMIODevicePropertyDeviceUID);
        CFStringRef deviceUid = NULL;
        UInt32 uidUsed = 0;
        if (CMIOObjectGetPropertyData(list[i], &uidAddress, 0, NULL, sizeof(deviceUid), &uidUsed, &deviceUid) != noErr || deviceUid == NULL) continue;
        BOOL match = [(__bridge NSString *)deviceUid caseInsensitiveCompare:uid] == NSOrderedSame;
        CFRelease(deviceUid);
        if (match) return list[i];
    }
    return 0;
}

/// The device's sink: the stream apps write into (CMIO direction 0, output).
static CMIOStreamID findSink(CMIOObjectID device) {
    CMIOObjectPropertyAddress streams = address(kCMIODevicePropertyStreams);
    UInt32 size = 0, used = 0;
    if (CMIOObjectGetPropertyDataSize(device, &streams, 0, NULL, &size) != noErr || size == 0) return 0;
    NSMutableData *ids = [NSMutableData dataWithLength:size];
    if (CMIOObjectGetPropertyData(device, &streams, 0, NULL, size, &used, ids.mutableBytes) != noErr) return 0;
    const CMIOStreamID *list = ids.bytes;
    for (size_t i = 0; i < used / sizeof(CMIOStreamID); i++) {
        CMIOObjectPropertyAddress directionAddress = address(kCMIOStreamPropertyDirection);
        UInt32 direction = 1, directionUsed = 0;
        if (CMIOObjectGetPropertyData(list[i], &directionAddress, 0, NULL, sizeof(direction), &directionUsed, &direction) == noErr && direction == 0) {
            return list[i];
        }
    }
    return 0;
}

static int probeLocked(void) {
    NSURL *app = [[NSWorkspace sharedWorkspace] URLForApplicationWithBundleIdentifier:kObsBundleId];
    if (app == nil) return ZF_OBSCAM_NO_OBS;
    NSString *uid = deviceUidInApp(app.path);
    if (uid == nil) return ZF_OBSCAM_NO_EXTENSION;
    CMIOObjectID device = findDevice(uid);
    if (device == 0) return ZF_OBSCAM_NOT_SET_UP;
    CMIOStreamID sink = findSink(device);
    if (sink == 0) return ZF_OBSCAM_NO_EXTENSION;
    if (!gRunning) {
        gDevice = device;
        gSink = sink;
    }
    return ZF_OBSCAM_READY;
}

/// Whether OBS's camera can be fed now (a ZF_OBSCAM_* code). Cheap enough
/// to call when the receiver's window comes to the front.
int zf_obscam_probe(void) {
    ensureLock();
    [gLock lock];
    int result = probeLocked();
    [gLock unlock];
    return result;
}

/// The sink tells us when the extension took a frame; the send path reads
/// the queue's count instead, so there is nothing to do here.
static void queueAltered(CMIOStreamID stream, void *token, void *refCon) { (void)stream; (void)token; (void)refCon; }

static void stopLocked(void) {
    if (gRunning) {
        CMIODeviceStopStream(gDevice, gSink);
        gRunning = false;
    }
    if (gQueue != NULL) {
        CFRelease(gQueue);
        gQueue = NULL;
    }
    if (gFormat != NULL) {
        CFRelease(gFormat);
        gFormat = NULL;
    }
}

/// Starts feeding the camera (idempotent). While started, the camera shows
/// what is sent; stopped, it shows OBS's own picture.
bool zf_obscam_start(void) {
    ensureLock();
    [gLock lock];
    bool ok = gRunning;
    if (!ok && probeLocked() == ZF_OBSCAM_READY) {
        CMSimpleQueueRef queue = NULL;
        if (CMIOStreamCopyBufferQueue(gSink, queueAltered, NULL, &queue) == noErr && queue != NULL) {
            gQueue = queue;
            ok = gRunning = CMIODeviceStartStream(gDevice, gSink) == noErr;
            if (!ok) stopLocked();
        }
    }
    [gLock unlock];
    return ok;
}

void zf_obscam_stop(void) {
    ensureLock();
    [gLock lock];
    stopLocked();
    [gLock unlock];
}

/// Sends one frame, stamped `seconds` on the host clock (the pacer's due
/// time). The buffer itself goes to the camera: no conversion, no copy.
int zf_obscam_send(CVPixelBufferRef pixelBuffer, double seconds) {
    ensureLock();
    [gLock lock];
    int result = ZF_OBSCAM_FAILED;
    if (gRunning && gQueue != NULL && pixelBuffer != NULL) {
        if (CMSimpleQueueGetCount(gQueue) >= CMSimpleQueueGetCapacity(gQueue)) {
            result = ZF_OBSCAM_QUEUE_FULL;
        } else {
            if (gFormat == NULL || !CMVideoFormatDescriptionMatchesImageBuffer(gFormat, pixelBuffer)) {
                if (gFormat != NULL) CFRelease(gFormat);
                gFormat = NULL;
                CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pixelBuffer, &gFormat);
            }
            CMSampleTimingInfo timing = { kCMTimeInvalid, CMTimeMakeWithSeconds(seconds, NSEC_PER_SEC), kCMTimeInvalid };
            CMSampleBufferRef sample = NULL;
            if (gFormat != NULL
                && CMSampleBufferCreateReadyWithImageBuffer(kCFAllocatorDefault, pixelBuffer, gFormat, &timing, &sample) == noErr) {
                // The queue now owns the sample's reference; the extension releases it.
                result = CMSimpleQueueEnqueue(gQueue, sample) == noErr ? ZF_OBSCAM_SENT : ZF_OBSCAM_FAILED;
                if (result != ZF_OBSCAM_SENT) CFRelease(sample);
            }
        }
    }
    [gLock unlock];
    return result;
}

/// For the tests: frames waiting in the sink's queue (-1 when stopped).
int zf_obscam_queued(void) {
    ensureLock();
    [gLock lock];
    int count = gQueue != NULL ? CMSimpleQueueGetCount(gQueue) : -1;
    [gLock unlock];
    return count;
}
